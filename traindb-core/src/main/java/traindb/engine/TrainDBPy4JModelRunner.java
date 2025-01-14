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

package traindb.engine;

import static java.lang.Thread.sleep;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.json.simple.JSONObject;
import py4j.GatewayServer;
import traindb.catalog.CatalogContext;
import traindb.catalog.pm.MModeltype;
import traindb.common.TrainDBConfiguration;
import traindb.common.TrainDBException;
import traindb.jdbc.TrainDBConnectionImpl;
import traindb.util.ZipUtils;

public class TrainDBPy4JModelRunner extends AbstractTrainDBModelRunner {

  public TrainDBPy4JModelRunner(
      TrainDBConnectionImpl conn, CatalogContext catalogContext, String modeltypeName,
      String modelName) {
    super(conn, catalogContext, modeltypeName, modelName);
  }

  public static String getModelRunnerPath() {
    return TrainDBConfiguration.getTrainDBPrefixPath() + "/models/TrainDBPy4JModelRunner.py";
  }

  private GatewayServer startGatewayServer() throws Exception {
    int javaPort = getAvailablePort();
    int pythonPort = getAvailablePort();

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PumpStreamHandler pumpStreamHandler = new PumpStreamHandler(outputStream);
    DefaultExecutor executor = new DefaultExecutor();
    executor.setStreamHandler(pumpStreamHandler);

    CommandLine cmdLine = CommandLine.parse("python");
    cmdLine.addArgument(getModelRunnerPath());
    cmdLine.addArgument(String.valueOf(javaPort));
    cmdLine.addArgument(String.valueOf(pythonPort));

    DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
    executor.execute(cmdLine, resultHandler);

    sleep(1000); // FIXME waiting the python process
    GatewayServer server = new GatewayServer(null, javaPort, pythonPort, 0, 0, null);
    server.start();
    return server;
  }

  @Override
  public void trainModel(JSONObject tableMetadata, String trainingDataQuery) throws Exception {
    // write metadata for model training scripts in python
    Path modelPath = getModelPath();
    Files.createDirectories(modelPath);

    // train ML model
    GatewayServer server = startGatewayServer();
    BasicDataSource ds = conn.getDataSource();
    Class jdbcClass = Class.forName(ds.getDriverClassName());
    MModeltype mModeltype = catalogContext.getModeltype(modeltypeName);
    try {
      TrainDBModelRunner modelRunner = (TrainDBModelRunner) server.getPythonServerEntryPoint(
          new Class[] { TrainDBModelRunner.class });
      modelRunner.connect(
          ds.getDriverClassName(), ds.getUrl(), ds.getUsername(), ds.getPassword(),
          jdbcClass.getProtectionDomain().getCodeSource().getLocation().getPath());
      modelRunner.trainModel(trainingDataQuery,
          mModeltype.getClassName(), TrainDBConfiguration.absoluteUri(mModeltype.getUri()),
          tableMetadata.toJSONString(), modelPath.toString());
    } catch (Exception e) {
      server.shutdown();
      e.printStackTrace();
      throw new TrainDBException("failed to train model");
    }
    server.shutdown();
  }

  @Override
  public void updateModel(JSONObject tableMetadata, String trainingDataQuery, String exModelName)
      throws Exception {
    throw new TrainDBException("Not supported yet in Py4J model runner");
  }

  @Override
  public void generateSynopsis(String outputPath, int rows) throws Exception {
    String modelPath = getModelPath().toString();
    MModeltype mModeltype = catalogContext.getModel(modelName).getModeltype();

    // generate synopsis from ML model
    GatewayServer server = startGatewayServer();
    try {
      TrainDBModelRunner modelRunner = (TrainDBModelRunner) server.getPythonServerEntryPoint(
          new Class[] { TrainDBModelRunner.class });
      modelRunner.generateSynopsis(
          mModeltype.getClassName(), TrainDBConfiguration.absoluteUri(mModeltype.getUri()),
          modelPath, rows, outputPath);
    } catch (Exception e) {
      server.shutdown();
      e.printStackTrace();
      throw new TrainDBException("failed to create synopsis");
    }
    server.shutdown();
  }

  @Override
  public String infer(String aggregateExpression, String groupByColumn, String whereCondition)
      throws Exception {
    throw new TrainDBException("Not supported yet in Py4J model runner");
  }

  @Override
  public String listHyperparameters(String className, String uri) throws Exception {
    GatewayServer server = startGatewayServer();
    String hyperparamsInfo;
    try {
      TrainDBModelRunner modelRunner = (TrainDBModelRunner) server.getPythonServerEntryPoint(
          new Class[] { TrainDBModelRunner.class });
      hyperparamsInfo = modelRunner.listHyperparameters(className,
          TrainDBConfiguration.absoluteUri(uri));
    } catch (Exception e) {
      server.shutdown();
      e.printStackTrace();
      throw new TrainDBException("failed to list hyperparameters");
    }
    server.shutdown();
    return hyperparamsInfo;
  }

  @Override
  public void exportModel(String outputPath) throws Exception {
    String modelPath = getModelPath().toString();
    ZipUtils.pack(modelPath, outputPath);
  }

  @Override
  public void importModel(byte[] zipModel, String uri) throws Exception {
    String modelPath = getModelPath().toString();
    ZipUtils.unpack(zipModel, modelPath);
  }

  @Override
  public void renameModel(String newModelName) throws Exception {
    String modelPath = getModelPath().toString();
    File oldDir = new File(modelPath);
    File newDir = new File(oldDir.getParent() + File.separator + newModelName);
    oldDir.renameTo(newDir);
  }

  private int getAvailablePort() throws Exception {
    ServerSocket s;
    try {
      s = new ServerSocket(0);
    } catch (IOException e) {
      throw new TrainDBException("failed to get an available port");
    }
    return s.getLocalPort();
  }
}