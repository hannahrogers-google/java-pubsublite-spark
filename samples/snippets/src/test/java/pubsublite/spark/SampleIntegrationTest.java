/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pubsublite.spark;

import static com.google.common.truth.Truth.assertThat;
import static pubsublite.spark.AdminUtils.createSubscriptionExample;
import static pubsublite.spark.AdminUtils.createTopicExample;
import static pubsublite.spark.AdminUtils.deleteSubscriptionExample;
import static pubsublite.spark.AdminUtils.deleteTopicExample;
import static pubsublite.spark.AdminUtils.subscriberExample;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.dataproc.v1.Job;
import com.google.cloud.dataproc.v1.JobControllerClient;
import com.google.cloud.dataproc.v1.JobControllerSettings;
import com.google.cloud.dataproc.v1.JobMetadata;
import com.google.cloud.dataproc.v1.JobPlacement;
import com.google.cloud.dataproc.v1.SparkJob;
import com.google.cloud.pubsublite.CloudRegion;
import com.google.cloud.pubsublite.CloudZone;
import com.google.cloud.pubsublite.ProjectId;
import com.google.cloud.pubsublite.ProjectNumber;
import com.google.cloud.pubsublite.SubscriptionName;
import com.google.cloud.pubsublite.SubscriptionPath;
import com.google.cloud.pubsublite.TopicName;
import com.google.cloud.pubsublite.TopicPath;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.pubsub.v1.PubsubMessage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.apache.maven.shared.utils.cli.CommandLineException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SampleIntegrationTest {

  private static final String CLOUD_REGION = "CLOUD_REGION";
  private static final String CLOUD_ZONE = "CLOUD_ZONE";
  private static final String PROJECT_NUMBER = "GOOGLE_CLOUD_PROJECT_NUMBER";
  private static final String PROJECT_ID = "PROJECT_ID";
  private static final String TOPIC_ID = "TOPIC_ID";
  private static final String CLUSTER_NAME = "CLUSTER_NAME";
  private static final String BUCKET_NAME = "BUCKET_NAME";
  private static final String SAMPLE_VERSION = "SAMPLE_VERSION";
  private static final String CONNECTOR_VERSION = "CONNECTOR_VERSION";

  private final String runId = UUID.randomUUID().toString();
  private CloudRegion cloudRegion;
  private CloudZone cloudZone;
  private ProjectNumber projectNumber;
  private ProjectId projectId;
  private TopicName sourceTopicId;
  private SubscriptionName sourceSubscriptionName;
  private SubscriptionPath sourceSubscriptionPath;
  private TopicName destinationTopicId;
  private TopicPath destinationTopicPath;
  private SubscriptionName destinationSubscriptionName;
  private SubscriptionPath destinationSubscriptionPath;
  private String clusterName;
  private String bucketName;
  private String workingDir;
  private String mavenHome;
  private String sampleVersion;
  private String connectorVersion;
  private String sampleJarName;
  private String connectorJarName;
  private String sampleJarNameInGCS;
  private String connectorJarNameInGCS;
  private String sampleJarLoc;
  private String connectorJarLoc;

  private void findMavenHome() throws Exception {
    Process p = Runtime.getRuntime().exec("mvn --version");
    BufferedReader stdOut = new BufferedReader(new InputStreamReader(p.getInputStream()));
    assertThat(p.waitFor()).isEqualTo(0);
    String s;
    while ((s = stdOut.readLine()) != null) {
      if (StringUtils.startsWith(s, "Maven home: ")) {
        mavenHome = s.replace("Maven home: ", "");
      }
    }
  }

  private void mavenPackage(String workingDir)
      throws MavenInvocationException, CommandLineException {
    InvocationRequest request = new DefaultInvocationRequest();
    request.setPomFile(new File(workingDir + "/pom.xml"));
    request.setGoals(ImmutableList.of("clean", "package", "-Dmaven.test.skip=true"));
    Invoker invoker = new DefaultInvoker();
    invoker.setMavenHome(new File(mavenHome));
    InvocationResult result = invoker.execute(request);
    if (result.getExecutionException() != null) {
      throw result.getExecutionException();
    }
    assertThat(result.getExitCode()).isEqualTo(0);
  }

  private void uploadGCS(Storage storage, String fileNameInGCS, String fileLoc) throws Exception {
    BlobId blobId = BlobId.of(bucketName, fileNameInGCS);
    BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
    storage.create(blobInfo, Files.readAllBytes(Paths.get(fileLoc)));
  }

  private Job runDataprocJob() throws Exception {
    String myEndpoint = String.format("%s-dataproc.googleapis.com:443", cloudRegion.value());
    JobControllerSettings jobControllerSettings =
        JobControllerSettings.newBuilder().setEndpoint(myEndpoint).build();

    try (JobControllerClient jobControllerClient =
        JobControllerClient.create(jobControllerSettings)) {
      JobPlacement jobPlacement = JobPlacement.newBuilder().setClusterName(clusterName).build();
      SparkJob sparkJob =
          SparkJob.newBuilder()
              .addJarFileUris(String.format("gs://%s/%s", bucketName, sampleJarNameInGCS))
              .addJarFileUris(String.format("gs://%s/%s", bucketName, connectorJarNameInGCS))
              .setMainClass("pubsublite.spark.WordCount")
              .addArgs(sourceSubscriptionPath.toString())
              .addArgs(destinationTopicPath.toString())
              .build();
      Job job = Job.newBuilder().setPlacement(jobPlacement).setSparkJob(sparkJob).build();
      OperationFuture<Job, JobMetadata> submitJobAsOperationAsyncRequest =
          jobControllerClient.submitJobAsOperationAsync(
              projectId.value(), cloudRegion.value(), job);
      return submitJobAsOperationAsyncRequest.get();
    }
  }

  private void verifyWordCountResult() {
    Map<String, Integer> expected = new HashMap<>();
    expected.put("the", 24);
    expected.put("of", 16);
    expected.put("and", 14);
    expected.put("i", 13);
    expected.put("my", 10);
    expected.put("a", 6);
    expected.put("in", 5);
    expected.put("that", 5);
    expected.put("soul", 4);
    expected.put("with", 4);
    expected.put("as", 3);
    expected.put("feel", 3);
    expected.put("like", 3);
    expected.put("me", 3);
    expected.put("so", 3);
    expected.put("then", 3);
    expected.put("us", 3);
    expected.put("when", 3);
    expected.put("which", 3);
    expected.put("am", 2);
    Map<String, Integer> actual = new HashMap<>();
    Queue<PubsubMessage> results =
        subscriberExample(
            cloudRegion.value(),
            cloudZone.zoneId(),
            projectNumber.value(),
            destinationSubscriptionName.value());
    for (PubsubMessage m : results) {
      String[] pair = m.getData().toStringUtf8().split("_");
      actual.put(pair[0], Integer.parseInt(pair[1]));
    }
    assertThat(actual).containsAtLeastEntriesIn(expected);
  }

  private void setUpVariables() {
    Map<String, String> env = System.getenv();
    Set<String> missingVars =
        Sets.difference(
            ImmutableSet.of(
                CLOUD_REGION,
                CLOUD_ZONE,
                PROJECT_NUMBER,
                TOPIC_ID,
                CLUSTER_NAME,
                BUCKET_NAME,
                SAMPLE_VERSION,
                CONNECTOR_VERSION),
            env.keySet());
    Preconditions.checkState(
        missingVars.isEmpty(), "Missing required environment variables: " + missingVars);
    cloudRegion = CloudRegion.of(env.get(CLOUD_REGION));
    cloudZone = CloudZone.of(cloudRegion, env.get(CLOUD_ZONE).charAt(0));
    projectId = ProjectId.of(env.get(PROJECT_ID));
    projectNumber = ProjectNumber.of(Long.parseLong(env.get(PROJECT_NUMBER)));
    sourceTopicId = TopicName.of(env.get(TOPIC_ID));
    sourceSubscriptionName = SubscriptionName.of("sample-integration-sub-source-" + runId);
    sourceSubscriptionPath =
        SubscriptionPath.newBuilder()
            .setProject(projectId)
            .setLocation(cloudZone)
            .setName(sourceSubscriptionName)
            .build();
    destinationTopicId = TopicName.of("sample-integration-topic-destination-" + runId);
    destinationTopicPath =
        TopicPath.newBuilder()
            .setProject(projectId)
            .setLocation(cloudZone)
            .setName(destinationTopicId)
            .build();
    destinationSubscriptionName =
        SubscriptionName.of("sample-integration-sub-destination-" + runId);
    destinationSubscriptionPath =
        SubscriptionPath.newBuilder()
            .setProject(projectId)
            .setLocation(cloudZone)
            .setName(destinationSubscriptionName)
            .build();
    clusterName = env.get(CLUSTER_NAME);
    bucketName = env.get(BUCKET_NAME);
    workingDir =
        System.getProperty("user.dir")
            .replace("/samples/snapshot", "")
            .replace("/samples/snippets", "");
    sampleVersion = env.get(SAMPLE_VERSION);
    connectorVersion = env.get(CONNECTOR_VERSION);
    sampleJarName = String.format("pubsublite-spark-snippets-%s.jar", sampleVersion);
    connectorJarName =
        String.format("pubsublite-spark-sql-streaming-%s-with-dependencies.jar", connectorVersion);
    sampleJarNameInGCS = String.format("pubsublite-spark-snippets-%s-%s.jar", sampleVersion, runId);
    connectorJarNameInGCS =
        String.format(
            "pubsublite-spark-sql-streaming-%s-with-dependencies-%s.jar", connectorVersion, runId);
    sampleJarLoc = String.format("%s/samples/snippets/target/%s", workingDir, sampleJarName);
    connectorJarLoc = String.format("%s/target/%s", workingDir, connectorJarName);
  }

  @Before
  public void setUp() throws Exception {
    setUpVariables();
    findMavenHome();

    // Create a subscription to read source word messages
    createSubscriptionExample(
        cloudRegion.value(),
        cloudZone.zoneId(),
        projectNumber.value(),
        sourceTopicId.value(),
        sourceSubscriptionName.value());

    // Create a topic and subscription for word count final results
    createTopicExample(
        cloudRegion.value(),
        cloudZone.zoneId(),
        projectNumber.value(),
        destinationTopicId.value(),
        /*partitions=*/ 1);
    createSubscriptionExample(
        cloudRegion.value(),
        cloudZone.zoneId(),
        projectNumber.value(),
        destinationTopicId.value(),
        destinationSubscriptionName.value());
  }

  @After
  public void tearDown() throws Exception {
    // Cleanup the topics and subscriptions
    deleteSubscriptionExample(cloudRegion.value(), sourceSubscriptionPath);
    deleteSubscriptionExample(cloudRegion.value(), destinationSubscriptionPath);
    deleteTopicExample(cloudRegion.value(), destinationTopicPath);
  }

  /** Note that source single word messages have been published to a permanent topic. */
  @Test
  public void test() throws Exception {
    // Maven package into jars
    mavenPackage(workingDir);
    mavenPackage(workingDir + "/samples");

    // Upload to GCS
    Storage storage =
        StorageOptions.newBuilder().setProjectId(projectId.value()).build().getService();
    uploadGCS(storage, sampleJarNameInGCS, sampleJarLoc);
    uploadGCS(storage, connectorJarNameInGCS, connectorJarLoc);

    // Run Dataproc job, block until it finishes
    runDataprocJob();

    // Verify final destination messages in Pub/Sub Lite
    verifyWordCountResult();
  }
}
