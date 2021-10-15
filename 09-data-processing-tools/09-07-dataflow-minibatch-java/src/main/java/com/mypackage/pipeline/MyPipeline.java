/*
 * Copyright (C) 2021 Google Inc.
 *
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

package com.mypackage.pipeline;

import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.JavaFieldSchema;
import org.apache.beam.sdk.schemas.annotations.DefaultSchema;
import org.apache.beam.sdk.schemas.annotations.SchemaCreate;
import org.apache.beam.sdk.schemas.transforms.Convert;
import org.apache.beam.sdk.schemas.transforms.Filter;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.JsonToRow;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.*;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyPipeline {

    /**
     * The logger to output status messages to.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MyPipeline.class);

    /**
     * The {@link Options} class provides the custom execution options passed by the
     * executor at the command-line.
     */
    public interface Options extends DataflowPipelineOptions {
    }

    /**
     * The main entry-point for pipeline execution. This method will start the
     * pipeline but will not wait for it's execution to finish. If blocking
     * execution is required, use the {@link MyPipeline#run(Options)} method to
     * start the pipeline and invoke {@code result.waitUntilFinish()} on the
     * {@link PipelineResult}.
     *
     * @param args The command-line args passed by the executor.
     */
    public static void main(String[] args) {
        Options options = PipelineOptionsFactory.fromArgs(args).as(Options.class);

        run(options);
    }

    public static Schema CommonLog = Schema.builder().addStringField("user_id").addStringField("ip")
            .addNullableField("lat", Schema.FieldType.DOUBLE).addNullableField("lng", Schema.FieldType.DOUBLE)
            .addStringField("timestamp").addStringField("http_request").addStringField("user_agent")
            .addInt64Field("http_response").addInt64Field("num_bytes").build();

    /**
     * Runs the pipeline to completion with the specified options. This method does
     * not wait until the pipeline is finished before returning. Invoke
     * {@code result.waitUntilFinish()} on the result object to block until the
     * pipeline is finished running if blocking programmatic execution is required.
     *
     * @param options The execution options.
     * @return The pipeline result.
     */
    public static PipelineResult run(Options options) {

        // Create the pipeline
        Pipeline pipeline = Pipeline.create(options);
        options.setJobName("my-pipeline-" + System.currentTimeMillis());

        // Static inputs and outputs
        String output = "dhodun1:weblog.dataflow_streaming";
        String topic = "projects/dhodun1/topics/events";
        Long amountCutoff = 400L;
        Integer minibatchFrequency = 60 * 5;

        /*
         * Steps: 1) Read something 2) Transform something 3) Write something
         */

        PCollection<Row> commonLogs = pipeline.apply("ReadFromPubSub", PubsubIO.readStrings().fromTopic(topic))
                .apply("JsonToRow", JsonToRow.withSchema(CommonLog));

        // Streaming Entries
        commonLogs
                .apply("FilterLargeAmounts",
                        Filter.<Row>create().whereFieldName("num_bytes", (Long num_bytes) -> num_bytes > amountCutoff))
                .apply("WriteToBQStreaming",
                        BigQueryIO.<Row>write().to(output).useBeamSchema()
                                .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                                .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));

        // commonLogs Entries
        commonLogs
                .apply("FilterSmallAmounts",
                        Filter.<Row>create().whereFieldName("num_bytes", (Long num_bytes) -> num_bytes <= amountCutoff))
                .apply("WriteToBQFileLoads", BigQueryIO.<Row>write().to(output).useBeamSchema()
                        .withMethod(BigQueryIO.Write.Method.FILE_LOADS)
                        .withTriggeringFrequency(Duration.standardSeconds(minibatchFrequency)).withNumFileShards(1)
                        .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                        .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));
        LOG.info("Building pipeline...");

        return pipeline.run();
    }
}