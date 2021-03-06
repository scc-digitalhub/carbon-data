/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.dataservices.core.odata;

import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.TableMetadata;
import org.apache.axis2.databinding.utils.ConverterUtil;
import org.apache.commons.codec.binary.Base64;
import org.wso2.carbon.dataservices.common.DBConstants;
import org.wso2.carbon.dataservices.core.DBUtils;
import org.wso2.carbon.dataservices.core.DataServiceFault;
import org.wso2.carbon.dataservices.core.odata.DataColumn.ODataDataType;
import org.wso2.carbon.dataservices.core.odata.expression.CassandraFilterExpressionVisitor;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.queryoption.CountOption;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;
import org.apache.olingo.server.api.uri.queryoption.FilterOption;
import org.apache.olingo.server.api.uri.queryoption.OrderByOption;
import org.apache.olingo.server.api.uri.queryoption.SelectOption;
import org.apache.olingo.server.api.uri.queryoption.SkipOption;
import org.apache.olingo.server.api.uri.queryoption.TopOption;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;


import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * This class implements cassandra datasource related operations for ODataDataHandler.
 *
 * @see ODataDataHandler
 */
public class CassandraDataHandler implements ODataDataHandler {

    /**
     * Table metadata.
     */
    private Map<String, Map<String, DataColumn>> tableMetaData;

    /**
     * Primary Keys of the Tables (Map<Table Name, List>).
     */
    private Map<String, List<String>> primaryKeys;

    /**
     * Config ID.
     */
    private final String configID;

    /**
     * List of Tables in the Database.
     */
    private List<String> tableList;

    /**
     * Cassandra session.
     */
    private final Session session;

    /**
     * Cassandra keyspace.
     */
    private final String keyspace;

    private ThreadLocal<Boolean> transactionAvailable = new ThreadLocal<Boolean>() {
        protected synchronized Boolean initialValue() {
            return false;
        }
    };

    private static final int RECORD_INSERT_STATEMENTS_CACHE_SIZE = 10000;

    private Map<String, PreparedStatement> preparedStatementMap =
            Collections.synchronizedMap(new LinkedHashMap<String, PreparedStatement>() {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, PreparedStatement> eldest) {
                    return super.size() > RECORD_INSERT_STATEMENTS_CACHE_SIZE;
                }
            });

    public CassandraDataHandler(String configID, Session session, String keyspace) {
        this.configID = configID;
        this.session = session;
        this.keyspace = keyspace;
        this.tableList = generateTableList();
        this.primaryKeys = generatePrimaryKeyList();
        this.tableMetaData = generateMetaData();
    }

    @Override
    public List<ODataEntry> readTable(String tableName, UriInfo uriInfo, List<Property> navProperties) throws ODataServiceFault, ExpressionVisitException, ODataApplicationException {
    	Statement statement = new SimpleStatement(generateCassandraQuery(tableName, uriInfo)); // generates the query to apply to the Cassandra database
        
        ResultSet resultSet = this.session.execute(statement);
        Iterator<Row> iterator = resultSet.iterator();
        List<ODataEntry> entryList = new ArrayList<>();
        ColumnDefinitions columnDefinitions = resultSet.getColumnDefinitions();
        while (iterator.hasNext()) {
            ODataEntry dataEntry = createDataEntryFromRow(tableName, iterator.next(), columnDefinitions);
            entryList.add(dataEntry);
        }
        return entryList;
    }
    
	public String generateCassandraQuery(String tableName, UriInfo uriInfo) throws ExpressionVisitException, ODataApplicationException, ODataServiceFault {
		SelectOption selectOpt = uriInfo.getSelectOption(); // extracts the various OData options
		ExpandOption expandOpt = uriInfo.getExpandOption();
		FilterOption filterOpt = uriInfo.getFilterOption();
		TopOption topOpt = uriInfo.getTopOption();
		OrderByOption orderByOpt = uriInfo.getOrderByOption();
		CountOption countOpt = uriInfo.getCountOption();
		SkipOption skipOpt = uriInfo.getSkipOption();
		
		String query = "SELECT "; // The query that will be returned by this method
		String select = "*"; // starts with *, changes it later if necessary
		if (expandOpt != null) { // $expand is not supported, due to Cassandra's lack of foreign keys
			throw new ODataApplicationException("The OData query option $expand is not supported by Cassandra. ",
					HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
		}
		if (selectOpt != null) { // OData $select option
			select = selectOpt.getText();
			if (select.contains("*")) { // if it contains *, it becomes SELECT * FROM
				select = "*";
			} else { // otherwise, only specific columns need to be selected
				
				// List of columns that are part of the primary key
				List<String> pKeysColumns = primaryKeys.get(tableName);
				
				// List of the columns listed in $select
				String[] selectArr = select.split(",");
				if (selectArr.length == 0) {
					throw new ODataApplicationException("No columns specified in the $select option. ",
							HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
				}
				List<String> selectColumns = Arrays.asList(selectArr);
				
				// Combines the two lists into the SELECT clause
				select = "";
				Iterator<String> iter = pKeysColumns.iterator();
				String strColumn;
				while (iter.hasNext()) {
					strColumn = iter.next();
					strColumn = CassandraUtils.preserveCase(strColumn); // Cassandra is case sensitive, yet it converts column names to lower case unless they're within double quotes
					select += strColumn;
					select += ","; // no need to check if it hasNext(), because we previously checked the next List cannot be empty
				}
				iter = selectColumns.iterator(); // adds the columns specified in $select
				while (iter.hasNext()) {
					strColumn = iter.next();
					if (pKeysColumns.contains(strColumn)) // column was already added
						continue;
					strColumn = CassandraUtils.preserveCase(strColumn); // Cassandra is case sensitive, yet it converts column names to lower case unless they're within double quotes
					select += strColumn;
					select += ","; // we remove the last comma after the loop
				}
				select = select.substring(0, select.length()-1);
			}
		}
		query += select + " FROM " + this.keyspace + "." + CassandraUtils.preserveCase(tableName); // the SELECT part is added
		if (filterOpt != null) { // OData $filter option
			// Visitor to apply the WHERE part; many operators are not supported by Cassandra
			CassandraFilterExpressionVisitor fev = new CassandraFilterExpressionVisitor();
			String where = " WHERE " + filterOpt.getExpression().accept(fev); // determines the WHERE part
			query += where; // the WHERE part is added
		}
		if (topOpt != null && orderByOpt == null && countOpt == null) { // OData $top option
			int limit;
			if (skipOpt != null) { // if $skip is present, add its value to $top's
				limit = topOpt.getValue() + skipOpt.getValue();
			} else { // if $skip is absent, simply take the value of $top
				limit = topOpt.getValue();
			}
			query += " LIMIT " + limit; // records to extract
		} else if (topOpt != null) { // $top should be applied after $orderby and $count, but it is currently impossible to do so in the DB query
			throw new ODataServiceFault("$top is currently not supported for Cassandra when used together with one of the following: $orderby, $count");
		}
		if (filterOpt != null) { // necessary for some WHERE conditions
			query += " ALLOW FILTERING";
		}
		query += ";"; // semicolon at the end of the query
		return query; // query is ready to be executed
	}

    @Override
    public List<ODataEntry> readTableWithKeys(String tableName, ODataEntry keys,UriInfo uriInfo) throws ODataServiceFault {
        List<ColumnMetadata> cassandraTableMetaData = this.session.getCluster().getMetadata().getKeyspace(this.keyspace)
                                                                  .getTable(CassandraUtils.preserveCase(tableName)).getColumns();
        List<String> pKeys = this.primaryKeys.get(tableName);
        String query = createReadSqlWithKeys(tableName, keys);
        List<Object> values = new ArrayList<>();
        for (String column : keys.getNames()) {
            if (this.tableMetaData.get(tableName).keySet().contains(column) && pKeys.contains(column)) {
                bindParams(column, keys.getValue(column), values, cassandraTableMetaData);
            }
        }
        PreparedStatement statement = this.preparedStatementMap.get(query);
        if (statement == null) {
            statement = this.session.prepare(query);
            this.preparedStatementMap.put(query, statement);
        }
        ResultSet resultSet = this.session.execute(statement.bind(values.toArray()));
        List<ODataEntry> entryList = new ArrayList<>();
        Iterator<Row> iterator = resultSet.iterator();
        ColumnDefinitions definitions = resultSet.getColumnDefinitions();
        while (iterator.hasNext()) {
            ODataEntry dataEntry = createDataEntryFromRow(tableName, iterator.next(), definitions);
            entryList.add(dataEntry);
        }
        return entryList;
    }
    
    @Override
    public int countRecords(UriInfo uriInfo, String tableName) throws ODataServiceFault, ExpressionVisitException, ODataApplicationException {
           return 0;
    }
    
    @Override
    public ODataEntry insertEntityToTable(String tableName, ODataEntry entity) throws ODataServiceFault {
        List<ColumnMetadata> cassandraTableMetaData = this.session.getCluster().getMetadata().getKeyspace(this.keyspace)
                                                                  .getTable(CassandraUtils.preserveCase(tableName)).getColumns();
        for (String pkey : this.primaryKeys.get(tableName)) {
            if (this.tableMetaData.get(tableName).get(pkey).getColumnType().equals(ODataDataType.GUID) &&
                entity.getValue(pkey) == null) {
                UUID uuid = UUID.randomUUID();
                entity.addValue(pkey, uuid.toString());
            }
        }
        String query = createInsertCQL(tableName, entity);
        List<Object> values = new ArrayList<>();
        for (DataColumn column : this.tableMetaData.get(tableName).values()) {
            String columnName = column.getColumnName();
            if (entity.getNames().contains(columnName) && entity.getValue(columnName) != null) {
                bindParams(columnName, entity.getValue(columnName), values, cassandraTableMetaData);
            }
        }
        PreparedStatement statement = this.preparedStatementMap.get(query);
        if (statement == null) {
            statement = this.session.prepare(query);
            this.preparedStatementMap.put(query, statement);
        }
        this.session.execute(statement.bind(values.toArray()));
        entity.addValue(ODataConstants.E_TAG, ODataUtils.generateETag(this.configID, tableName, entity));
        return entity;
    }

    @Override
    public boolean deleteEntityInTable(String tableName, ODataEntry entity) throws ODataServiceFault {
        if (transactionAvailable.get()) {
            return deleteEntityInTableTransactional(tableName, entity);
        } else {
            return deleteEntityTableNonTransactional(tableName, entity);
        }
    }

    private boolean deleteEntityTableNonTransactional(String tableName, ODataEntry entity) throws ODataServiceFault {
        List<ColumnMetadata> cassandraTableMetaData = this.session.getCluster().getMetadata().getKeyspace(this.keyspace)
                                                                  .getTable(CassandraUtils.preserveCase(tableName)).getColumns();
        List<String> pKeys = this.primaryKeys.get(tableName);
        String query = createDeleteCQL(tableName);
        List<Object> values = new ArrayList<>();
        for (String column : entity.getNames()) {
            if (pKeys.contains(column)) {
                bindParams(column, entity.getValue(column), values, cassandraTableMetaData);
            }
        }
        PreparedStatement statement = this.preparedStatementMap.get(query);
        if (statement == null) {
            statement = this.session.prepare(query);
            this.preparedStatementMap.put(query, statement);
        }
        ResultSet result = this.session.execute(statement.bind(values.toArray()));
        return result.wasApplied();
    }

    private boolean deleteEntityInTableTransactional(String tableName, ODataEntry entity) throws ODataServiceFault {
        List<ColumnMetadata> cassandraTableMetaData = this.session.getCluster().getMetadata().getKeyspace(this.keyspace)
                                                                  .getTable(CassandraUtils.preserveCase(tableName)).getColumns();
        List<String> pKeys = this.primaryKeys.get(tableName);
        String query = createDeleteTransactionalCQL(tableName, entity);
        List<Object> values = new ArrayList<>();
        for (String column : entity.getNames()) {
            if (pKeys.contains(column)) {
                bindParams(column, entity.getValue(column), values, cassandraTableMetaData);
            }
        }
        for (String column : entity.getNames()) {
            if (!pKeys.contains(column)) {
                bindParams(column, entity.getValue(column), values, cassandraTableMetaData);
            }
        }
        PreparedStatement statement = this.preparedStatementMap.get(query);
        if (statement == null) {
            statement = this.session.prepare(query);
            this.preparedStatementMap.put(query, statement);
        }
        ResultSet result = this.session.execute(statement.bind(values.toArray()));
        return result.wasApplied();
    }

    @Override
    public boolean updateEntityInTable(String tableName, ODataEntry newProperties) throws ODataServiceFault {
        List<ColumnMetadata> cassandraTableMetaData = this.session.getCluster().getMetadata().getKeyspace(this.keyspace)
                                                                  .getTable(CassandraUtils.preserveCase(tableName)).getColumns();
        List<String> pKeys = this.primaryKeys.get(tableName);
        String query = createUpdateEntityCQL(tableName, newProperties);
        List<Object> values = new ArrayList<>();
        for (String column : newProperties.getNames()) {
            if (this.tableMetaData.get(tableName).keySet().contains(column) && !pKeys.contains(column)) {
                bindParams(column, newProperties.getValue(column), values, cassandraTableMetaData);
            }
        }
        for (String column : newProperties.getNames()) {
            if (pKeys.contains(column)) {
                bindParams(column, newProperties.getValue(column), values, cassandraTableMetaData);
            }
        }
        PreparedStatement statement = this.preparedStatementMap.get(query);
        if (statement == null) {
            statement = this.session.prepare(query);
            this.preparedStatementMap.put(query, statement);
        }
        ResultSet result = this.session.execute(statement.bind(values.toArray()));
        return result.wasApplied();
    }

    public boolean updateEntityInTableTransactional(String tableName, ODataEntry oldProperties,
                                                    ODataEntry newProperties) throws ODataServiceFault {
        List<ColumnMetadata> cassandraTableMetaData =
                this.session.getCluster().getMetadata().getKeyspace(this.keyspace).getTable(CassandraUtils.preserveCase(tableName)).getColumns();
        List<String> pKeys = this.primaryKeys.get(tableName);
        String query = createUpdateEntityTransactionalCQL(tableName, oldProperties, newProperties);
        List<Object> values = new ArrayList<>();
        for (String column : newProperties.getNames()) {
            if (this.tableMetaData.get(tableName).keySet().contains(column) && !pKeys.contains(column)) {
                bindParams(column, newProperties.getValue(column), values, cassandraTableMetaData);
            }
        }
        for (String column : oldProperties.getNames()) {
            if (pKeys.contains(column)) {
                bindParams(column, oldProperties.getValue(column), values, cassandraTableMetaData);
            }
        }
        for (String column : oldProperties.getNames()) {
            if (!pKeys.contains(column)) {
                bindParams(column, oldProperties.getValue(column), values, cassandraTableMetaData);
            }
        }
        PreparedStatement statement = this.preparedStatementMap.get(query);
        if (statement == null) {
            statement = this.session.prepare(query);
            this.preparedStatementMap.put(query, statement);
        }
        ResultSet result = this.session.execute(statement.bind(values.toArray()));
        return result.wasApplied();
    }

    @Override
    public Map<String, Map<String, DataColumn>> getTableMetadata() {
        return this.tableMetaData;
    }

    @Override
    public List<String> getTableList() {
        return this.tableList;
    }

    @Override
    public Map<String, List<String>> getPrimaryKeys() {
        return this.primaryKeys;
    }

    @Override
    public Map<String, NavigationTable> getNavigationProperties() {
        return null;
    }

    @Override
    public void openTransaction() throws ODataServiceFault {
        this.transactionAvailable.set(true);
        // doesn't support
    }

    @Override
    public void commitTransaction() {
        this.transactionAvailable.set(false);
        //doesn't support
    }

    @Override
    public void rollbackTransaction() throws ODataServiceFault {
        this.transactionAvailable.set(false);
        //doesn't support
    }

    @Override
    public void updateReference(String rootTableName, ODataEntry rootTableKeys, String navigationTable,
                                ODataEntry navigationTableKeys) throws ODataServiceFault {
        throw new ODataServiceFault("Cassandra datasources doesn't support references.");
    }

    @Override
    public void deleteReference(String rootTableName, ODataEntry rootTableKeys, String navigationTable,
                                ODataEntry navigationTableKeys) throws ODataServiceFault {
        throw new ODataServiceFault("Cassandra datasources doesn't support references.");
    }

    /**
     * This method wraps result set data in to DataEntry and creates a list of DataEntry.
     *
     * @param tableName         Table Name
     * @param row               Row
     * @param columnDefinitions Column Definition
     * @return DataEntry
     * @throws ODataServiceFault
     */
    private ODataEntry createDataEntryFromRow(String tableName, Row row, ColumnDefinitions columnDefinitions)
            throws ODataServiceFault {
        String paramValue;
        ODataEntry entry = new ODataEntry();
        //Creating a unique string to represent the row
        try {
            for (int i = 0; i < columnDefinitions.size(); i++) {
                String columnName = columnDefinitions.getName(i);
                DataType columnType = columnDefinitions.getType(i);

                switch (columnType.getName()) {
                    case ASCII:
                        paramValue = row.getString(i);
                        break;
                    case BIGINT:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getLong(i));
                        break;
                    case BLOB:
                        paramValue = this.base64EncodeByteBuffer(row.getBytes(i));
                        break;
                    case BOOLEAN:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getBool(i));
                        break;
                    case COUNTER:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getLong(i));
                        break;
                    case DECIMAL:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getDecimal(i));
                        break;
                    case DOUBLE:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getDouble(i));
                        break;
                    case FLOAT:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getFloat(i));
                        break;
                    case INET:
                        paramValue = row.getInet(i).toString();
                        break;
                    case INT:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getInt(i));
                        break;
                    case TEXT:
                        paramValue = row.getString(i);
                        break;
                    case TIME:
                        // There was no handler for this type in the previous implementation
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getTime(i));
                        break;
                    case TIMESTAMP:
                        // The previous implementation used getDate, which caused an exception
                        paramValue = row.isNull(i) ? null : CassandraUtils.SDF.format(row.getTimestamp(i));
                        break;
                    case UUID:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getUUID(i));
                        break;
                    case VARCHAR:
                        paramValue = row.getString(i);
                        break;
                    case VARINT:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getVarint(i));
                        break;
                    case TIMEUUID:
                        paramValue = row.isNull(i) ? null : ConverterUtil.convertToString(row.getUUID(i));
                        break;
                    case LIST:
                        paramValue = row.isNull(i) ? null : Arrays.toString(row.getList(i, Object.class).toArray());
                        break;
                    case SET:
                        paramValue = row.isNull(i) ? null : row.getSet(i, Object.class).toString();
                        break;
                    case MAP:
                        paramValue = row.isNull(i) ? null : row.getMap(i, Object.class, Object.class).toString();
                        break;
                    case UDT:
                        paramValue = row.isNull(i) ? null : row.getUDTValue(i).toString();
                        break;
                    case TUPLE:
                        paramValue = row.isNull(i) ? null : row.getTupleValue(i).toString();
                        break;
                    case CUSTOM:
                        paramValue = row.isNull(i) ? null : this.base64EncodeByteBuffer(row.getBytes(i));
                        break;
                    default:
                        paramValue = row.getString(i);
                        break;
                }
                entry.addValue(columnName, paramValue);
            }
        } catch (DataServiceFault e) {
            throw new ODataServiceFault(e, "Error occurred when creating OData entry. :" + e.getMessage());
        }
        //Set E-Tag to the entity
        entry.addValue("ETag", ODataUtils.generateETag(this.configID, tableName, entry));
        return entry;
    }

    private List<String> generateTableList() {
        List<String> tableList = new ArrayList<>();
        for (TableMetadata tableMetadata : this.session.getCluster().getMetadata().getKeyspace(this.keyspace)
                                                       .getTables()) {
            tableList.add(tableMetadata.getName());
        }
        return tableList;
    }

    private Map<String, List<String>> generatePrimaryKeyList() {
        Map<String, List<String>> primaryKeyMap = new HashMap<>();
        for (String tableName : this.tableList) {
            List<String> primaryKey = new ArrayList<>();
            for (ColumnMetadata columnMetadata : this.session.getCluster().getMetadata().getKeyspace(this.keyspace)
                                                             .getTable(CassandraUtils.preserveCase(tableName)).getPrimaryKey()) {
                primaryKey.add(columnMetadata.getName());
            }
            primaryKeyMap.put(tableName, primaryKey);
        }
        return primaryKeyMap;
    }

    private Map<String, Map<String, DataColumn>> generateMetaData() {
        Map<String, Map<String, DataColumn>> metadata = new HashMap<>();
        for (String tableName : this.tableList) {
            Map<String, DataColumn> dataColumnMap = new HashMap<>();
            for (ColumnMetadata columnMetadata : this.session.getCluster().getMetadata().getKeyspace(this.keyspace)
                                                             .getTable(CassandraUtils.preserveCase(tableName)).getColumns()) {
                DataColumn dataColumn;
                if (this.primaryKeys.get(tableName).contains(columnMetadata.getName())) {
                    dataColumn = new DataColumn(columnMetadata.getName(),
                                                getDataType(columnMetadata.getType().getName()), false);
                } else {
                    dataColumn = new DataColumn(columnMetadata.getName(),
                                                getDataType(columnMetadata.getType().getName()), true);
                }
                dataColumnMap.put(dataColumn.getColumnName(), dataColumn);
            }
            metadata.put(tableName, dataColumnMap);
        }
        return metadata;
    }

    private void bindParams(String columnName, String value, List<Object> values, List<ColumnMetadata> metaData)
            throws ODataServiceFault {
        DataType.Name dataType = null;
        for (ColumnMetadata columnMetadata : metaData) {
            if (columnMetadata.getName().equals(columnName)) {
                dataType = columnMetadata.getType().getName();
                break;
            }
        }
        if (dataType == null) {
            throw new ODataServiceFault("Error occurred when binding data. DataType was missing for " +
                                        columnName + " column.");
        }
        try {
            switch (dataType) {
                case ASCII:
                /* fall through */
                case TEXT:
                /* fall through */
                case VARCHAR:
                /* fall through */
                case TIMEUUID:
                    values.add(value);
                    break;
                case UUID:
                    values.add(value == null ? null : UUID.fromString(value));
                    break;
                case BIGINT:
                    values.add(value == null ? null : Long.parseLong(value));
                    break;
                case VARINT:
                /* fall through */
                case COUNTER:
                    values.add(value == null ? null : value);
                    break;
                case BLOB:
                    values.add(value == null ? null : this.base64DecodeByteBuffer(value));
                    break;
                case BOOLEAN:
                    values.add(value == null ? null : Boolean.parseBoolean(value));
                    break;
                case DECIMAL:
                    values.add(value == null ? null : new BigDecimal(value));
                    break;
                case DOUBLE:
                    values.add(value == null ? null : Double.parseDouble(value));
                    break;
                case FLOAT:
                    values.add(value == null ? null : Float.parseFloat(value));
                    break;
                case INT:
                    values.add(value == null ? null : Integer.parseInt(value));
                    break;
                case TIMESTAMP:
                    values.add(value == null ? null : DBUtils.getTimestamp(value));
                    break;
                case TIME:
                    values.add(value == null ? null : DBUtils.getTime(value));
                    break;
                case DATE:
                    values.add(value == null ? null : DBUtils.getDate(value));
                    break;
                default:
                    values.add(value);
                    break;
            }
        } catch (Exception e) {
            throw new ODataServiceFault(e, "Error occurred when binding data. :" + e.getMessage());
        }
    }

    private ODataDataType getDataType(DataType.Name dataTypeName) {
        ODataDataType dataType;
        switch (dataTypeName) {
            case ASCII:
                /* fall through */
            case TEXT:
                /* fall through */
            case VARCHAR:
                /* fall through */
            case TIMEUUID:
                dataType = ODataDataType.STRING;
                break;
            case UUID:
                dataType = ODataDataType.GUID;
                break;
            case BIGINT:
                /* fall through */
            case VARINT:
                /* fall through */
            case COUNTER:
                dataType = ODataDataType.INT64;
                break;
            case BLOB:
                dataType = ODataDataType.BINARY;
                break;
            case BOOLEAN:
                dataType = ODataDataType.BOOLEAN;
                break;
            case DECIMAL:
                /* fall through */
            case FLOAT:
                dataType = ODataDataType.DECIMAL;
                break;
            case DOUBLE:
                dataType = ODataDataType.DOUBLE;
                break;
            case INT:
                dataType = ODataDataType.INT32;
                break;
            case TIMESTAMP:
                dataType = ODataDataType.DATE_TIMEOFFSET;
                break;
            case TIME:
                dataType = ODataDataType.TIMEOFDAY;
                break;
            case DATE:
                dataType = ODataDataType.DATE;
                break;
            default:
                dataType = ODataDataType.STRING;
                break;
        }
        return dataType;
    }

    /**
     * This method creates a CQL query to update data.
     *
     * @param tableName     Name of the table
     * @param newProperties update entry
     * @return sql Query
     */
    private String createUpdateEntityCQL(String tableName, ODataEntry newProperties) {
        List<String> pKeys = this.primaryKeys.get(tableName);
        StringBuilder sql = new StringBuilder();
        // preserveCase is necessary because, if a table or column name is not within double quotes, Cassandra will automatically convert it to lower case
        sql.append("UPDATE ").append(CassandraUtils.preserveCase(tableName)).append(" SET ");
        boolean propertyMatch = false;
        for (String column : newProperties.getNames()) {
            if (!pKeys.contains(column)) {
                if (propertyMatch) {
                    sql.append(",");
                }
                sql.append(CassandraUtils.preserveCase(column)).append(" = ").append(" ? ");
                propertyMatch = true;
            }
        }
        sql.append(" WHERE ");
        // Handling keys
        propertyMatch = false;
        for (String key : pKeys) {
            if (propertyMatch) {
                sql.append(" AND ");
            }
            sql.append(CassandraUtils.preserveCase(key)).append(" = ").append(" ? ");
            propertyMatch = true;
        }
        return sql.toString();
    }

    /**
     * This method creates a CQL query to update data.
     *
     * @param tableName     Name of the table
     * @param oldProperties old Properties
     * @param newProperties update entry
     * @return sql Query
     */
    private String createUpdateEntityTransactionalCQL(String tableName, ODataEntry oldProperties,
                                                      ODataEntry newProperties) {
        List<String> pKeys = this.primaryKeys.get(tableName);
        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(CassandraUtils.preserveCase(tableName)).append(" SET ");
        boolean propertyMatch = false;
        for (String column : newProperties.getNames()) {
            if (!pKeys.contains(column)) {
                if (propertyMatch) {
                    sql.append(",");
                }
                sql.append(CassandraUtils.preserveCase(column)).append(" = ").append(" ? ");
                propertyMatch = true;
            }
        }
        sql.append(" WHERE ");
        // Handling keys
        propertyMatch = false;
        for (String key : pKeys) {
            if (propertyMatch) {
                sql.append(" AND ");
            }
            sql.append(CassandraUtils.preserveCase(key)).append(" = ").append(" ? ");
            propertyMatch = true;
        }
        sql.append(" IF ");
        propertyMatch = false;
        for (String column : oldProperties.getNames()) {
            if (!pKeys.contains(column)) {
                if (propertyMatch) {
                    sql.append(" AND ");
                }
                sql.append(CassandraUtils.preserveCase(column)).append(" = ").append(" ? ");
                propertyMatch = true;
            }
        }
        return sql.toString();
    }

    /**
     * This method creates a CQL query to insert data in table.
     *
     * @param tableName Name of the table
     * @return sqlQuery
     */
    private String createInsertCQL(String tableName, ODataEntry entry) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(CassandraUtils.preserveCase(tableName)).append(" (");
        boolean propertyMatch = false;
        for (DataColumn column : this.tableMetaData.get(tableName).values()) {
            if (entry.getValue(column.getColumnName()) != null) {
                if (propertyMatch) {
                    sql.append(",");
                }
                sql.append(CassandraUtils.preserveCase(column.getColumnName()));
                propertyMatch = true;
            }
        }
        sql.append(" ) VALUES ( ");
        propertyMatch = false;
        for (DataColumn column : this.tableMetaData.get(tableName).values()) {
            if (entry.getValue(column.getColumnName()) != null) {
                if (propertyMatch) {
                    sql.append(",");
                }
                sql.append(" ? ");
                propertyMatch = true;
            }
        }
        sql.append(" ) ");
        return sql.toString();
    }

    /**
     * This method creates CQL query to read data with keys.
     *
     * @param tableName Name of the table
     * @param keys      Keys
     * @return sql Query
     */
    private String createReadSqlWithKeys(String tableName, ODataEntry keys) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(CassandraUtils.preserveCase(tableName)).append(" WHERE ");
        boolean propertyMatch = false;
        for (DataColumn column : this.tableMetaData.get(tableName).values()) {
            if (keys.getValue(column.getColumnName()) != null) {
                if (propertyMatch) {
                    sql.append(" AND ");
                }
                sql.append(CassandraUtils.preserveCase(column.getColumnName())).append(" = ").append(" ? ");
                propertyMatch = true;
            }
        }
        return sql.toString();
    }

    /**
     * This method creates CQL query to delete data.
     *
     * @param tableName Name of the table
     * @return sql Query
     */
    private String createDeleteCQL(String tableName) {
        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ").append(CassandraUtils.preserveCase(tableName)).append(" WHERE ");
        List<String> pKeys = this.primaryKeys.get(tableName);
        boolean propertyMatch = false;
        for (String key : pKeys) {
            if (propertyMatch) {
                sql.append(" AND ");
            }
            sql.append(CassandraUtils.preserveCase(key)).append(" = ").append(" ? ");
            propertyMatch = true;
        }
        return sql.toString();
    }

    /**
     * This method creates CQL query to delete data.
     *
     * @param tableName Name of the table
     * @return sql Query
     */
    private String createDeleteTransactionalCQL(String tableName, ODataEntry entry) {
        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ").append(CassandraUtils.preserveCase(tableName)).append(" WHERE ");
        List<String> pKeys = this.primaryKeys.get(tableName);
        boolean propertyMatch = false;
        for (String key : entry.getNames()) {
            if (pKeys.contains(key)) {
                if (propertyMatch) {
                    sql.append(" AND ");
                }
                sql.append(CassandraUtils.preserveCase(key)).append(" = ").append(" ? ");
                propertyMatch = true;
            }
        }
        sql.append(" IF ");
        propertyMatch = false;
        for (String column : entry.getNames()) {
            if (!pKeys.contains(column)) {
                if (propertyMatch) {
                    sql.append(" AND ");
                }
                sql.append(CassandraUtils.preserveCase(column)).append(" = ").append(" ? ");
                propertyMatch = true;
            }
        }
        return sql.toString();
    }

    private String base64EncodeByteBuffer(ByteBuffer byteBuffer) throws ODataServiceFault {
    	if (byteBuffer == null)
    		return null;
        byte[] data = byteBuffer.array();
        byte[] base64Data = Base64.encodeBase64(data);
        try {
            return new String(base64Data, DBConstants.DEFAULT_CHAR_SET_TYPE);
        } catch (UnsupportedEncodingException e) {
            throw new ODataServiceFault(e, "Error in encoding result binary data: " + e.getMessage());
        }
    }

    private ByteBuffer base64DecodeByteBuffer(String data) throws ODataServiceFault {
        try {
            byte[] buff = Base64.decodeBase64(data.getBytes(DBConstants.DEFAULT_CHAR_SET_TYPE));
            ByteBuffer result = ByteBuffer.allocate(buff.length);
            result.put(buff);
            return result;
        } catch (UnsupportedEncodingException e) {
            throw new ODataServiceFault(e, "Error in decoding input base64 data: " + e.getMessage());
        }
    }

}
