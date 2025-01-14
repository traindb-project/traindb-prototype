/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package traindb.catalog;

import com.google.common.collect.ImmutableMap;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.jdo.Transaction;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import traindb.catalog.pm.MColumn;
import traindb.catalog.pm.MJoin;
import traindb.catalog.pm.MModel;
import traindb.catalog.pm.MModeltype;
import traindb.catalog.pm.MQueryLog;
import traindb.catalog.pm.MSchema;
import traindb.catalog.pm.MSynopsis;
import traindb.catalog.pm.MTableExt;
import traindb.catalog.pm.MTable;
import traindb.catalog.pm.MTask;
import traindb.catalog.pm.MTrainingStatus;
import traindb.common.TrainDBLogger;

public final class JDOCatalogContext implements CatalogContext {

  private static final TrainDBLogger LOG = TrainDBLogger.getLogger(JDOCatalogContext.class);
  private final PersistenceManager pm;

  private static final String JOIN_TABLE_PREFIX = "__JOIN_";

  public JDOCatalogContext(PersistenceManager persistenceManager) {
    pm = persistenceManager;
  }

  @Override
  public boolean modeltypeExists(String name) {
    return getModeltype(name) != null;
  }

  @Override
  public @Nullable MModeltype getModeltype(String name) {
    try {
      Query query = pm.newQuery(MModeltype.class);
      setFilterPatterns(query, ImmutableMap.of("modeltype_name", name));
      query.setUnique(true);

      return (MModeltype) query.execute(name);
    } catch (RuntimeException e) {
    }
    return null;
  }

  @Override
  public Collection<MModeltype> getModeltypes() throws CatalogException {
    return getModeltypes(ImmutableMap.of());
  }

  @Override
  public Collection<MModeltype> getModeltypes(Map<String, Object> filterPatterns)
      throws CatalogException {
    try {
      Query query = pm.newQuery(MModeltype.class);
      setFilterPatterns(query, filterPatterns);
      return (List<MModeltype>) query.execute();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get modeltypes", e);
    }
  }

  @Override
  public MModeltype createModeltype(String name, String type, String location, String className,
                                    String uri, String hyperparameters) throws CatalogException {
    try {
      MModeltype mModeltype = new MModeltype(name, type, location, className, uri, hyperparameters);
      pm.makePersistent(mModeltype);
      return mModeltype;
    } catch (RuntimeException e) {
      throw new CatalogException("failed to create modeltype '" + name + "'", e);
    }
  }

  @Override
  public void dropModeltype(String name) throws CatalogException {
    Transaction tx = pm.currentTransaction();
    try {
      tx.begin();

      pm.deletePersistent(getModeltype(name));

      tx.commit();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to drop modeltype '" + name + "'", e);
    } finally {
      if (tx.isActive()) {
        tx.rollback();
      }
    }
  }

  private MTable addTable(String schemaName, String tableName, List<String> columnNames,
                          RelDataType relDataType, String tableType) throws CatalogException {
    MTable mTable;
    try {
      MSchema mSchema = getSchema(schemaName);
      if (mSchema == null) {
        mSchema = new MSchema(schemaName);
        pm.makePersistent(mSchema);
      }

      mTable = getTable(schemaName, tableName);
      if (mTable == null) {
        mTable = new MTable(tableName, tableType, mSchema);
        pm.makePersistent(mTable);

        List<RelDataTypeField> fields = relDataType.getFieldList();
        for (int i = 0; i < relDataType.getFieldCount(); i++) {
          RelDataTypeField field = fields.get(i);
          MColumn mColumn = new MColumn(field.getName(),
              field.getType().getSqlTypeName().getJdbcOrdinal(),
              field.getType().getPrecision(),
              field.getType().getScale(),
              field.getType().isNullable(),
              mTable);

          pm.makePersistent(mColumn);
        }
      }
    } catch (RuntimeException e) {
      throw new CatalogException("failed to add table '" + schemaName + "." + tableName + "'", e);
    }
    return mTable;
  }

  @Override
  public MTable createJoinTable(List<String> schemaNames, List<String> tableNames,
                                List<List<String>> columnNames, List<RelDataType> dataTypes,
                                String joinCondition) throws CatalogException {
    List<Long> srcTableIds = new ArrayList<>();
    for (int i = 0; i < tableNames.size(); i++) {
      MTable table = addTable(schemaNames.get(i), tableNames.get(i), columnNames.get(i),
          dataTypes.get(i), "TABLE");
      srcTableIds.add(table.getId());
    }

    UUID joinTableId = UUID.randomUUID();
    String joinTableName = JOIN_TABLE_PREFIX + joinTableId;
    try {
      // use first table's schema as join table's schema
      MTable joinTable =  new MTable(joinTableName, "JOIN", getSchema(schemaNames.get(0)));
      pm.makePersistent(joinTable);
      MTableExt joinTableExt = new MTableExt(joinTableName, "JOIN", joinCondition, joinTable);
      pm.makePersistent(joinTableExt);

      for (int i = 0; i < tableNames.size(); i++) {
        List<String> colnames = columnNames.get(i);
        RelDataType relDataType = dataTypes.get(i);
        for (String colname : colnames) {
          RelDataTypeField field = relDataType.getField(colname, true, false);
          MColumn mColumn = new MColumn(field.getName(),
              field.getType().getSqlTypeName().getJdbcOrdinal(),
              field.getType().getPrecision(),
              field.getType().getScale(),
              field.getType().isNullable(),
              joinTable);

          pm.makePersistent(mColumn);
        }
      }

      for (int i = 0; i < tableNames.size(); i++) {
        MJoin join = new MJoin(joinTable.getId(), srcTableIds.get(i), columnNames.get(i));
        pm.makePersistent(join);
      }

      return joinTable;
    } catch (RuntimeException e) {
      throw new CatalogException("failed to create join table", e);
    }
  }

  @Override
  public void dropJoinTable(String schemaName, String joinTableName) throws CatalogException {
    Transaction tx = pm.currentTransaction();
    try {
      tx.begin();

      MTable joinTable = getTable(schemaName, joinTableName);
      if (joinTable == null) {
        return;
      }

      Query query = pm.newQuery(MJoin.class);
      setFilterPatterns(query, ImmutableMap.of("join_table_id", joinTable.getId()));
      Collection<MJoin> mJoins = (List<MJoin>) query.execute();
      pm.deletePersistentAll(mJoins);
      pm.deletePersistent(joinTable);

      tx.commit();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to drop join table '" + joinTableName + "'", e);
    } finally {
      if (tx.isActive()) {
        tx.rollback();
      }
    }
  }

  @Override
  public Collection<MSynopsis> getJoinSynopses(
      List<Long> baseTableIds, Map<Long, List<String>> columnNames, String joinCondition)
      throws CatalogException {
    try {
      List<Long> joinTableIds = null;
      for (Long tid : baseTableIds) {
        Query query = pm.newQuery(MJoin.class);
        setFilterPatterns(query, ImmutableMap.of("src_table_id", tid));
        List<MJoin> mJoins = (List<MJoin>) query.execute();
        List<Long> ids = mJoins.stream()
            .filter(obj -> obj.containsColumnNames(columnNames.get(tid)))
            .map(MJoin::getJoinTableId).collect(Collectors.toList());
        if (joinTableIds == null) {
          joinTableIds = ids;
        } else {
          joinTableIds.retainAll(ids);
        }
      }
      if (joinTableIds == null) {
        return null;
      }

      List<MSynopsis> joinSynopses = new ArrayList<>();
      for (Long joinTableId : joinTableIds) {
        Query query = pm.newQuery(MJoin.class);
        setFilterPatterns(query, ImmutableMap.of("join_table_id", joinTableId));
        List<MJoin> mJoins = (List<MJoin>) query.execute();
        if (baseTableIds.size() != mJoins.size()) {
          continue;
        }

        Collection<MTable> joinTable = getTables(ImmutableMap.of("id", joinTableId));
        for (MTable jt : joinTable) {
          Collection<MTableExt> tableExts = jt.getTableExts();
          if (tableExts == null || tableExts.isEmpty()) {
            continue;
          }
          for (MTableExt tableExt : tableExts) {
            if (tableExt.getExternalTableUri().contains(joinCondition)) {
              Collection<MSynopsis> synopses =
                  getAllSynopses(jt.getSchema().getSchemaName(), jt.getTableName());
              joinSynopses.addAll(synopses);
              break;
            }
          }
        }
      }
      return joinSynopses;
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get synopses", e);
    }

  }

  @Override
  public MModel trainModel(
      String modeltypeName, String modelName, String schemaName, String tableName,
      List<String> columnNames, RelDataType dataType, @Nullable Long baseTableRows,
      @Nullable Long trainedRows, @Nullable String options) throws CatalogException {
    MTable mTable = getTable(schemaName, tableName);
    if (mTable == null) {
      mTable = addTable(schemaName, tableName, columnNames, dataType, "TABLE");
    }
    try {

      Set<String> columnNameSet = new HashSet<>();
      Iterator<String> iter = columnNames.iterator();
      while (iter.hasNext()) {
        String colname = iter.next();
        if (!columnNameSet.add(colname)) {
          iter.remove();
        }
      }
      MModeltype mModeltype = getModeltype(modeltypeName);
      MModel mModel = new MModel(
          mModeltype, modelName, schemaName, tableName, columnNames,
          baseTableRows, trainedRows, options == null ? "" : options, mTable);
      pm.makePersistent(mModel);

      if (mModeltype.getLocation().equals("REMOTE")) {
        MTrainingStatus mTrainingStatus = new MTrainingStatus(modelName, "TRAINING",
            new Timestamp(System.currentTimeMillis()), mModel);
        pm.makePersistent(mTrainingStatus);
      }

      return mModel;
    } catch (RuntimeException e) {
      throw new CatalogException("failed to train model '" + modelName + "'", e);
    }
  }

  @Override
  public void dropModel(String name) throws CatalogException {
    MModel mModel = getModel(name);
    String baseSchema = mModel.getSchemaName();
    String baseTable = mModel.getTableName();

    Transaction tx = pm.currentTransaction();
    try {
      tx.begin();
      pm.deletePersistent(mModel);
      tx.commit();

      if (baseTable.startsWith(JOIN_TABLE_PREFIX)) {
        dropJoinTable(baseSchema, baseTable);
      }

      Collection<MModel> baseTableModels =
          getModels(ImmutableMap.of("schema_name", baseSchema, "table_name", baseTable));
      if (baseTableModels == null || baseTableModels.size() == 0) {
        MTable mTable = getTable(baseSchema, baseTable);
        tx.begin();
        pm.deletePersistent(mTable);
        tx.commit();
      }

      Collection<MModel> baseSchemaModels = getModels(ImmutableMap.of("schema_name", baseSchema));
      if (baseSchemaModels == null || baseSchemaModels.size() == 0) {
        MSchema mSchema = getSchema(baseSchema);
        tx.begin();
        pm.deletePersistent(mSchema);
        tx.commit();
      }

      Collection<MTrainingStatus> trainingStatus =
          getTrainingStatus(ImmutableMap.of("model_name", name));
      if (trainingStatus != null && trainingStatus.size() > 0) {
        tx.begin();
        pm.deletePersistentAll(trainingStatus);
        tx.commit();
      }
    } catch (RuntimeException e) {
      throw new CatalogException("failed to drop model '" + name + "'", e);
    } finally {
      if (tx.isActive()) {
        tx.rollback();
      }
    }
  }

  @Override
  public Collection<MModel> getModels() throws CatalogException {
    return getModels(ImmutableMap.of());
  }

  @Override
  public Collection<MModel> getModels(Map<String, Object> filterPatterns) throws CatalogException {
    try {
      Query query = pm.newQuery(MModel.class);
      setFilterPatterns(query, filterPatterns);
      return (List<MModel>) query.execute();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get models", e);
    }
  }

  @Override
  public Collection<MModel> getInferenceModels(String baseSchema, String baseTable)
      throws CatalogException {
    return getModels(ImmutableMap.of(
        "schema_name", baseSchema, "table_name", baseTable, "modeltype.category", "INFERENCE"));
  }

  @Override
  public boolean modelExists(String name) {
    return getModel(name) != null;
  }

  @Override
  public @Nullable MModel getModel(String name) {
    try {
      Query query = pm.newQuery(MModel.class);
      setFilterPatterns(query, ImmutableMap.of("model_name", name));
      query.setUnique(true);

      return (MModel) query.execute(name);
    } catch (RuntimeException e) {
    }
    return null;
  }

  @Override
  public Collection<MTrainingStatus> getTrainingStatus(Map<String, Object> filterPatterns)
      throws CatalogException {
    try {
      Query query = pm.newQuery(MTrainingStatus.class);
      setFilterPatterns(query, filterPatterns);
      return (List<MTrainingStatus>) query.execute();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get training status", e);
    }
  }

  @Override
  public void updateTrainingStatus(String modelName, String status) throws CatalogException {
    try {
      Query query = pm.newQuery(MTrainingStatus.class);
      setFilterPatterns(query, ImmutableMap.of("model_name", modelName));
      List<MTrainingStatus> trainingStatus = (List<MTrainingStatus>) query.execute();
      Comparator<MTrainingStatus> comparator = Comparator.comparing(MTrainingStatus::getStartTime);
      MTrainingStatus latestStatus = trainingStatus.stream().max(comparator).get();
      latestStatus.setTrainingStatus(status);
      pm.makePersistent(latestStatus);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get training status", e);
    }
  }

  private void importTable(String schemaName, JSONObject jsonTableMetadata) {
    MSchema mSchema = getSchema(schemaName);
    if (mSchema == null) {
      mSchema = pm.makePersistent(new MSchema(schemaName));
    }

    String tableName = (String) jsonTableMetadata.get("tableName");
    MTable mTable = getTable(schemaName, tableName);
    if (mTable == null) {
      JSONObject jsonTable = (JSONObject) jsonTableMetadata.get("table");
      mTable = pm.makePersistent(
          new MTable(tableName, (String) jsonTable.get("tableType"), mSchema));

      JSONArray jsonColumns = (JSONArray) jsonTable.get("columns");
      for (int i = 0; i < jsonColumns.size(); i++) {
        JSONObject jsonColumn = (JSONObject) jsonColumns.get(i);
        String columnName = (String) jsonColumn.get("columnName");
        int columnType = ((Long) jsonColumn.get("columnType")).intValue();
        int precision = ((Long) jsonColumn.get("precision")).intValue();
        int scale = ((Long) jsonColumn.get("scale")).intValue();
        boolean nullable = (boolean) jsonColumn.get("nullable");
        MColumn mColumn = new MColumn(columnName, columnType, precision, scale, nullable, mTable);
        pm.makePersistent(mColumn);
      }
    }
  }

  @Override
  public void importModel(String modeltypeName, String modelName, JSONObject exportMetadata)
      throws CatalogException {
    try {
      String schemaName = (String) exportMetadata.get("schemaName");
      JSONObject jsonTable = (JSONObject) exportMetadata.get("table");
      importTable(schemaName, jsonTable);

      String tableName = (String) exportMetadata.get("tableName");
      MTable mTable = getTable(schemaName, tableName);
      MModeltype mModeltype = getModeltype(modeltypeName);
      JSONArray jsonColumnNames = (JSONArray) exportMetadata.get("columnNames");
      List<String> columnNames = new ArrayList<>();
      for (int i = 0; i < jsonColumnNames.size(); i++) {
        columnNames.add((String) jsonColumnNames.get(i));
      }
      String options = (String) exportMetadata.get("modelOptions");
      Long baseTableRows = (Long) exportMetadata.get("tableRows");
      Long trainedRows = (Long) exportMetadata.get("trainedRows");
      MModel mModel = new MModel(
          mModeltype, modelName, schemaName, tableName, columnNames,
          baseTableRows, trainedRows, options == null ? "" : options, mTable);
      pm.makePersistent(mModel);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to import model '" + modelName + "'", e);
    }
  }

  @Override
  public void renameModel(String modelName, String newModelName) throws CatalogException {
    try {
      MModel mModel = getModel(modelName);
      mModel.setModelName(newModelName);

      Collection<MTrainingStatus> mTrainingStatus =
          getTrainingStatus(ImmutableMap.of("model_name", modelName));
      for (MTrainingStatus st : mTrainingStatus) {
        st.setModelName(newModelName);
      }

      Collection<MSynopsis> mSynopses = getAllSynopses(ImmutableMap.of("model_name", modelName));
      for (MSynopsis mSynopsis : mSynopses) {
        mSynopsis.setModelName(newModelName);
      }

      pm.makePersistent(mModel);
      pm.makePersistentAll(mTrainingStatus);
      pm.makePersistentAll(mSynopses);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to rename model", e);
    }
  }

  @Override
  public void enableModel(String modelName) throws CatalogException {
    try {
      MModel mModel = getModel(modelName);
      mModel.enableModel();
      pm.makePersistent(mModel);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to enable model", e);
    }
  }

  @Override
  public void disableModel(String modelName) throws CatalogException {
    try {
      MModel mModel = getModel(modelName);
      mModel.disableModel();
      pm.makePersistent(mModel);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to disable model", e);
    }
  }

  @Override
  public MSynopsis createSynopsis(String synopsisName, String modelName, Integer rows,
                                  @Nullable Double ratio, boolean isExternal)
      throws CatalogException {
    try {
      MModel mModel = getModel(modelName);
      MSynopsis mSynopsis = new MSynopsis(synopsisName, rows, ratio, mModel, isExternal,
          mModel.getTable());
      pm.makePersistent(mSynopsis);
      return mSynopsis;
    } catch (RuntimeException e) {
      throw new CatalogException("failed to create synopsis '" + synopsisName + "'", e);
    }
  }

  @Override
  public Collection<MSynopsis> getAllSynopses() throws CatalogException {
    return getAllSynopses(ImmutableMap.of());
  }

  @Override
  public Collection<MSynopsis> getAllSynopses(String baseSchema, String baseTable)
      throws CatalogException {
    return getAllSynopses(ImmutableMap.of("schema_name", baseSchema, "table_name", baseTable));
  }

  @Override
  public Collection<MSynopsis> getAllSynopses(Map<String, Object> filterPatterns)
      throws CatalogException {
    try {
      Query query = pm.newQuery(MSynopsis.class);
      setFilterPatterns(query, filterPatterns);
      return (List<MSynopsis>) query.execute();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get synopses", e);
    }
  }

  @Override
  public boolean synopsisExists(String name) {
    return getSynopsis(name) != null;
  }

  @Override
  public @Nullable MSynopsis getSynopsis(String name) {
    try {
      Query query = pm.newQuery(MSynopsis.class);
      setFilterPatterns(query, ImmutableMap.of("synopsis_name", name));
      query.setUnique(true);

      return (MSynopsis) query.execute(name);
    } catch (RuntimeException e) {
    }
    return null;
  }

  @Override
  public void dropSynopsis(String name) throws CatalogException {
    Transaction tx = pm.currentTransaction();
    try {
      tx.begin();

      MSynopsis mSynopsis = getSynopsis(name);
      if (externalTableExists(name)) {
        pm.deletePersistent(getTable(mSynopsis.getSchemaName(), name));
      }
      pm.deletePersistent(mSynopsis);

      tx.commit();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to drop synopsis '" + name + "'", e);
    } finally {
      if (tx.isActive()) {
        tx.rollback();
      }
    }
  }

  @Override
  public void importSynopsis(String synopsisName, boolean isExternal, JSONObject exportMetadata)
      throws CatalogException {
    try {
      String schemaName = (String) exportMetadata.get("schemaName");
      JSONObject jsonTable = (JSONObject) exportMetadata.get("table");
      importTable(schemaName, jsonTable);

      String tableName = (String) exportMetadata.get("tableName");
      MTable mTable = getTable(schemaName, tableName);
      JSONArray jsonColumnNames = (JSONArray) exportMetadata.get("columnNames");
      List<String> columnNames = new ArrayList<>();
      for (int i = 0; i < jsonColumnNames.size(); i++) {
        columnNames.add((String) jsonColumnNames.get(i));
      }

      Integer rows = ((Long) exportMetadata.get("rows")).intValue();
      Double ratio = (Double) exportMetadata.get("ratio");

      MSynopsis mSynopsis = new MSynopsis(synopsisName, rows, ratio, "-", schemaName, tableName,
          columnNames, isExternal, mTable);
      pm.makePersistent(mSynopsis);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to import synopsis '" + synopsisName + "'", e);
    }

  }

  @Override
  public void renameSynopsis(String synopsisName, String newSynopsisName) throws CatalogException {
    try {
      MSynopsis mSynopsis = getSynopsis(synopsisName);
      mSynopsis.setSynopsisName(newSynopsisName);
      pm.makePersistent(mSynopsis);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to rename synopsis", e);
    }
  }

  @Override
  public void enableSynopsis(String synopsisName) throws CatalogException {
    try {
      MSynopsis mSynopsis = getSynopsis(synopsisName);
      mSynopsis.enableSynopsis();
      pm.makePersistent(mSynopsis);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to enable synopsis", e);
    }
  }

  @Override
  public void disableSynopsis(String synopsisName) throws CatalogException {
    try {
      MSynopsis mSynopsis = getSynopsis(synopsisName);
      mSynopsis.disableSynopsis();
      pm.makePersistent(mSynopsis);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to disable synopsis", e);
    }
  }

  @Override
  public void updateSynopsisStatistics(String synopsisName, String statistics)
      throws CatalogException {
    try {
      MSynopsis mSynopsis = getSynopsis(synopsisName);
      mSynopsis.setSynopsisStatistics(statistics);
      pm.makePersistent(mSynopsis);
    } catch (RuntimeException e) {
      throw new CatalogException("failed to update synopsis statistics '" + synopsisName + "'", e);
    }
  }

  @Override
  public MTableExt createExternalTable(String name, String format, String uri)
      throws CatalogException {
    try {
      MSynopsis mSynopsis = getSynopsis(name);
      MSchema mSchema = mSynopsis.getTable().getSchema();
      MTable mTable = new MTable(name, "FOREIGN_TABLE", mSchema);
      pm.makePersistent(mTable);

      Collection<MColumn> mColumns = mSynopsis.getTable().getColumns();
      List<String> synColumnNames = mSynopsis.getColumnNames();
      for (String synColumnName : synColumnNames) {
        for (MColumn mColumn : mColumns) {
          if (mColumn.getColumnName().equals(synColumnName)) {
            MColumn mSynColumn = new MColumn(
                mColumn.getColumnName(), mColumn.getColumnType(), mColumn.getPrecision(),
                mColumn.getScale(), mColumn.isNullable(), mTable);
            pm.makePersistent(mSynColumn);
            break;
          }
        }
      }

      MTableExt mTableExt = new MTableExt(name, format, uri, mTable);
      pm.makePersistent(mTableExt);

      return mTableExt;
    } catch (RuntimeException e) {
      throw new CatalogException("failed to create external table '" + name + "'", e);
    }
  }

  @Override
  public boolean externalTableExists(String name) {
    return getExternalTable(name) != null;
  }

  @Override
  public @Nullable MTableExt getExternalTable(String name) {
    try {
      Query query = pm.newQuery(MTableExt.class);
      setFilterPatterns(query, ImmutableMap.of("table_name", name));
      query.setUnique(true);

      return (MTableExt) query.execute(name);
    } catch (RuntimeException e) {
    }
    return null;
  }

  @Override
  public @Nullable MSchema getSchema(String name) {
    try {
      Query query = pm.newQuery(MSchema.class);
      setFilterPatterns(query, ImmutableMap.of("schema_name", name));
      query.setUnique(true);

      return (MSchema) query.execute(name);
    } catch (RuntimeException e) {
    }
    return null;
  }

  @Override
  public Collection<MSchema> getSchemas() throws CatalogException {
    return getSchemas(ImmutableMap.of());
  }

  @Override
  public Collection<MSchema> getSchemas(Map<String, Object> filterPatterns)
      throws CatalogException {
    try {
      Query query = pm.newQuery(MSchema.class);
      setFilterPatterns(query, filterPatterns);
      return (List<MSchema>) query.execute();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get schemas", e);
    }
  }

  @Override
  public @Nullable MTable getTable(String schemaName, String tableName) {
    try {
      Query query = pm.newQuery(MTable.class);
      setFilterPatterns(query,
          ImmutableMap.of("table_name", tableName, "schema.schema_name", schemaName));
      query.setUnique(true);

      return (MTable) query.execute(tableName, schemaName);
    } catch (RuntimeException e) {
    }
    return null;
  }

  @Override
  public Collection<MTable> getTables(Map<String, Object> filterPatterns)
      throws CatalogException {
    try {
      Query query = pm.newQuery(MTable.class);
      setFilterPatterns(query, filterPatterns);
      return (List<MTable>) query.execute();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get schemas", e);
    }
  }

  @Override
  public Collection<MQueryLog> getQueryLogs() throws CatalogException {
    return getQueryLogs(ImmutableMap.of());
  }

  @Override
  public Collection<MQueryLog> getQueryLogs(Map<String, Object> filterPatterns)
      throws CatalogException {
    try {
      Query query = pm.newQuery(MQueryLog.class);
      setFilterPatterns(query, filterPatterns);
      return (List<MQueryLog>) query.execute();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get query log", e);
    }
  }

  @Override
  public MQueryLog insertQueryLog(String start, String user, String query) throws CatalogException {
    try {
      MQueryLog mQuery = new MQueryLog(start, user, query);
      pm.makePersistent(mQuery);
      return mQuery;
    } catch (RuntimeException e) {
      throw new CatalogException("failed to create query log '" + query + "'", e);
    }
  }

  @Override
  public void deleteQueryLogs(Integer cnt) throws CatalogException {
    Transaction tx = pm.currentTransaction();
    try {
      tx.begin();

      Query query = pm.newQuery(MQueryLog.class);

      if (cnt > 0) {
        query.range(0, cnt);
      }

      Collection<MTask> ret = (List<MTask>) query.execute();
      pm.deletePersistentAll(ret);

      //pm.deletePersistent(getQueryLog());
      tx.commit();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to delete query log", e);
    } finally {
      if (tx.isActive()) {
        tx.rollback();
      }
    }
  }

  @Override
  public Collection<MTask> getTaskLogs() throws CatalogException {
    return getTaskLogs(ImmutableMap.of());
  }

  @Override
  public Collection<MTask> getTaskLogs(Map<String, Object> filterPatterns) throws CatalogException {
    try {
      Query query = pm.newQuery(MTask.class);
      setFilterPatterns(query, filterPatterns);
      return (List<MTask>) query.execute();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to get task log", e);
    }
  }

  @Override
  public MTask insertTask(String time, Integer idx, String task, String status)
      throws CatalogException {
    try {
      MTask mtask = new MTask(time, idx, task, status);
      pm.makePersistent(mtask);
      return mtask;
    } catch (RuntimeException e) {
      throw new CatalogException("failed to create task log '" + task + "'", e);
    }
  }

  @Override
  public void deleteTasks(Integer cnt) throws CatalogException {
    Transaction tx = pm.currentTransaction();
    try {
      tx.begin();

      Query query = pm.newQuery(MTask.class);

      if (cnt > 0) {
        query.range(0, cnt);
      }

      Collection<MTask> ret = (List<MTask>) query.execute();

      pm.deletePersistentAll(ret);
      //pm.deletePersistent(getTaskLog());

      tx.commit();
    } catch (RuntimeException e) {
      throw new CatalogException("failed to delete task log", e);
    } finally {
      if (tx.isActive()) {
        tx.rollback();
      }
    }
  }

  @Override
  public void close() {
    pm.close();
  }

  private void setFilterPatterns(Query query, Map<String, Object> filterPatterns) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Object> entry : filterPatterns.entrySet()) {
      Object v = entry.getValue();
      if (v instanceof String) {
        sb.append(entry.getKey()).append(".matches('").append(v).append("')");
      } else {
        sb.append(entry.getKey()).append(" == ").append(v);
      }
      sb.append(" && ");
    }
    String filterStr = sb.toString();
    if (filterStr.length() > 0) {
      query.setFilter(filterStr.substring(0, filterStr.length() - 4 /* " && " */));
    }
  }
}
