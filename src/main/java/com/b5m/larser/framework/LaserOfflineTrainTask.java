package com.b5m.larser.framework;

import java.io.IOException;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.mahout.common.HadoopUtil;
import org.msgpack.type.Value;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.b5m.HDFSHelper;
import com.b5m.admm.AdmmOptimizerDriver;
import com.b5m.conf.Configuration;
import com.b5m.larser.feature.LaserMessageConsumer;
import com.b5m.larser.offline.topn.LaserOfflineResultWriter;
import com.b5m.larser.offline.topn.LaserOfflineTopNDriver;
import com.b5m.msgpack.MsgpackClient;

public class LaserOfflineTrainTask implements Job {
	private static final Logger LOG = LoggerFactory
			.getLogger(LaserOfflineTrainTask.class);

	public void execute(JobExecutionContext context)
			throws JobExecutionException {
		String collection = context.getJobDetail().getGroup();
		LOG.info("Oline Train Task for {}", collection);

		final MsgpackClient client = new MsgpackClient(
				com.b5m.conf.Configuration.getInstance().getMsgpackAddress(
						collection), com.b5m.conf.Configuration.getInstance()
						.getMsgpackPort(collection), collection);

		final Path outputPath = Configuration.getInstance()
				.getLaserOfflineOutput(collection);
		final Integer iterationsMaximum = Configuration.getInstance()
				.getMaxIteration(collection);
		final Float regularizationFactor = Configuration.getInstance()
				.getRegularizationFactor(collection);
		final Boolean addIntercept = Configuration.getInstance().addIntercept(
				collection);
		final org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
		FileSystem fs = null;
		try {
			fs = FileSystem.get(conf);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		conf.set("mapred.job.queue.name", "sf1");
		conf.set("com.b5m.msgpack.collection", collection);

		try {
			final LaserMessageConsumer consumeTask = (LaserMessageConsumer) context
					.getJobDetail().getJobDataMap()
					.get("com.b5m.laser.message.consumer");


			Path admmOutput = new Path(outputPath, "ADMM");
			Path input = consumeTask.nextOfflinePath();
			AdmmOptimizerDriver.run(input, admmOutput,
					regularizationFactor, addIntercept, null,
					iterationsMaximum, conf);
			HadoopUtil.delete(conf, input);

			Value res = client.asyncRead(new Object[0], "isNeedTopN");
			Boolean isNeedTopN = res.asBooleanValue().getBoolean();
			LaserOfflineResultWriter writer = new LaserOfflineResultWriter();
			writer.write(collection, !isNeedTopN, fs, new Path(admmOutput,
					AdmmOptimizerDriver.FINAL_MODEL));
			if (isNeedTopN) {
				LOG.info("calculating offline topn clusters for each user, write results to msgpack");
				LaserOfflineTopNDriver.run(collection, Configuration
						.getInstance().getTopNClustering(collection), conf);
			}

		} catch (Exception e) {
			LOG.info(e.getMessage());
		} 
	}
}
