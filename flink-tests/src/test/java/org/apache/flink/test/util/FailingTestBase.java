/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.util;

import akka.actor.ActorRef;
import org.junit.Assert;

import org.apache.flink.runtime.client.JobClient;
import org.apache.flink.runtime.client.JobExecutionException;
import org.apache.flink.runtime.jobgraph.JobGraph;

/**
 * Base class for integration tests which test whether the system recovers from failed executions.
 */
public abstract class FailingTestBase extends RecordAPITestBase {
	/**
	 * Returns the {@link JobGraph} of the failing job. 
	 * 
	 * @return The JobGraph of the failing job.
	 * @throws Exception
	 */
	abstract protected JobGraph getFailingJobGraph() throws Exception;
	
	/**
	 * Returns the path to the jar-file of the failing job.
	 * 
	 * @return Path to the jar-file of the failing job.
	 */
	protected String getFailingJarFilePath() {
		return null;
	}
	
	/**
	 * Returns the timeout for the execution of both (the failing and the working) job in seconds.
	 * 
	 * @return Timeout for the execution of both jobs in seconds.
	 */
	abstract protected int getTimeout();
	
	/**
	 * Tests that both jobs, the failing and the working one, are handled correctly.
	 * The first (failing) job must be canceled and the client must report the failure.
	 * The second (working) job must finish successfully and compute the correct result.
	 * A timeout waits for the successful return for the client. In case of a deadlock 
	 * (or too small value for timeout) the time runs out and this test fails. 
	 * 
	 */
	@Override
	public void testJob() throws Exception {
		startCluster();
		try {
			// pre-submit
			try {
				preSubmit();
			}
			catch (Exception e) {
				System.err.println(e.getMessage());
				e.printStackTrace();
				Assert.fail("Pre-submit work caused an error: " + e.getMessage());
			}
			
			// init submission thread
			SubmissionThread st = new SubmissionThread(Thread.currentThread(), this.executor, getFailingJobGraph(), getJobGraph());
			// start submission thread
			st.start();
			
			try {
				// wait for timeout
				Thread.sleep(getTimeout()*1000);
				Assert.fail("Failing job and successful job did not fail.");
			} catch(InterruptedException ie) {
				// will have happened if all works fine
			}
			
			Exception cte = st.error;
			if (cte != null) {
				cte.printStackTrace();
				Assert.fail("Task Canceling failed: " + cte.getMessage());
			}
			
			// post-submit
			try {
				postSubmit();
			}
			catch (Exception e) {
				System.err.println(e.getMessage());
				e.printStackTrace();
				Assert.fail("Post-submit work caused an error: " + e.getMessage());
			}
		}
		finally {
			stopCluster();
		}
	}
	
	/**
	 * Thread for submitting both jobs sequentially to the test cluster.
	 * First, the failing job is submitted. The working job is submitted after the client returns 
	 * from the call of its submitJobAndWait() method. 
	 */
	private class SubmissionThread extends Thread {

		// reference to the timeout thread
		private final Thread timeoutThread;
		// cluster to submit the job to.
		private final ForkableFlinkMiniCluster executor;
		// job graph of the failing job (submitted first)
		private final JobGraph failingJob;
		// job graph of the working job (submitted after return from failing job)
		private final JobGraph job;
		
		private volatile Exception error;
		

		public SubmissionThread(Thread timeoutThread, ForkableFlinkMiniCluster executor, JobGraph
				failingJob,	JobGraph job) {
			this.timeoutThread = timeoutThread;
			this.executor = executor;
			this.failingJob = failingJob;
			this.job = job;
		}
		
		/**
		 * Submits the failing and the working job sequentially to the cluster.
		 * As soon as the second job finishes, the timeout thread is interrupted and this thread closed.
		 */
		@Override
		public void run() {
			ActorRef client = this.executor.getJobClient();

			try {
				// submit failing job
				JobClient.submitJobAndWait(this.failingJob, false, client, executor.timeout());
				
				this.error = new Exception("The job did not fail.");
			} catch(JobExecutionException jee) {
				// as expected
			} catch (Exception e) {
				this.error = e;
			}
			
			
			try {
				// submit working job
				JobClient.submitJobAndWait(this.job, false, client, executor.timeout());
			} catch (Exception e) {
				this.error = e;
			}
			
			// interrupt timeout thread
			timeoutThread.interrupt();
		}
	}
}
