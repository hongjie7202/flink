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

package org.apache.flink.runtime.taskmanager;

import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.configuration.IllegalConfigurationException;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.testutils.CommonTestUtils;
import org.apache.flink.runtime.concurrent.Executors;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServicesUtils;
import org.junit.Test;

import scala.Tuple2;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.*;
import java.util.Iterator;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Validates that the TaskManager startup properly obeys the configuration
 * values.
 */
public class TaskManagerConfigurationTest {

	@Test
	public void testUsePreconfiguredNetworkInterface() throws Exception {
		final String TEST_HOST_NAME = "testhostname";

		Configuration config = new Configuration();
		config.setString(ConfigConstants.TASK_MANAGER_HOSTNAME_KEY, TEST_HOST_NAME);
		config.setString(JobManagerOptions.ADDRESS, "localhost");
		config.setInteger(JobManagerOptions.PORT, 7891);

		HighAvailabilityServices highAvailabilityServices = HighAvailabilityServicesUtils.createHighAvailabilityServices(
			config,
			Executors.directExecutor(),
			HighAvailabilityServicesUtils.AddressResolution.NO_ADDRESS_RESOLUTION);

		try {

			Tuple2<String, Iterator<Integer>> address = TaskManager.selectNetworkInterfaceAndPortRange(config, highAvailabilityServices);

			// validate the configured test host name
			assertEquals(TEST_HOST_NAME, address._1());
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		} finally {
			highAvailabilityServices.closeAndCleanupAllData();
		}
	}

	@Test
	public void testActorSystemPortConfig() throws Exception {
		// config with pre-configured hostname to speed up tests (no interface selection)
		Configuration config = new Configuration();
		config.setString(ConfigConstants.TASK_MANAGER_HOSTNAME_KEY, "localhost");
		config.setString(JobManagerOptions.ADDRESS, "localhost");
		config.setInteger(JobManagerOptions.PORT, 7891);

		HighAvailabilityServices highAvailabilityServices = HighAvailabilityServicesUtils.createHighAvailabilityServices(
			config,
			Executors.directExecutor(),
			HighAvailabilityServicesUtils.AddressResolution.NO_ADDRESS_RESOLUTION);

		try {
			// auto port
			Iterator<Integer> portsIter = TaskManager.selectNetworkInterfaceAndPortRange(config, highAvailabilityServices)._2();
			assertTrue(portsIter.hasNext());
			assertEquals(0, (int) portsIter.next());

			// pre-defined port
			final int testPort = 22551;
			config.setString(TaskManagerOptions.RPC_PORT, String.valueOf(testPort));

			portsIter = TaskManager.selectNetworkInterfaceAndPortRange(config, highAvailabilityServices)._2();
			assertTrue(portsIter.hasNext());
			assertEquals(testPort, (int) portsIter.next());

			// port range
			config.setString(TaskManagerOptions.RPC_PORT, "8000-8001");
			portsIter = TaskManager.selectNetworkInterfaceAndPortRange(config, highAvailabilityServices)._2();
			assertTrue(portsIter.hasNext());
			assertEquals(8000, (int) portsIter.next());
			assertEquals(8001, (int) portsIter.next());

			// invalid port
			try {
				config.setString(TaskManagerOptions.RPC_PORT, "-1");
				TaskManager.selectNetworkInterfaceAndPortRange(config, highAvailabilityServices);
				fail("should fail with an exception");
			}
			catch (IllegalConfigurationException e) {
				// bam!
			}

			// invalid port
			try {
				config.setString(TaskManagerOptions.RPC_PORT, "100000");
				TaskManager.selectNetworkInterfaceAndPortRange(config, highAvailabilityServices);
				fail("should fail with an exception");
			}
			catch (IllegalConfigurationException e) {
				// bam!
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		} finally {
			highAvailabilityServices.closeAndCleanupAllData();
		}
	}

	@Test
	public void testDefaultFsParameterLoading() {
		final File tmpDir = getTmpDir();
		final File confFile =  new File(tmpDir, GlobalConfiguration.FLINK_CONF_FILENAME);

		try {
			final URI defaultFS = new URI("otherFS", null, "localhost", 1234, null, null, null);

			final PrintWriter pw1 = new PrintWriter(confFile);
			pw1.println("fs.default-scheme: "+ defaultFS);
			pw1.close();

			String[] args = new String[]{"--configDir:" + tmpDir};
			TaskManager.parseArgsAndLoadConfig(args);

			Field f = FileSystem.class.getDeclaredField("defaultScheme");
			f.setAccessible(true);
			URI scheme = (URI) f.get(null);

			assertEquals("Default Filesystem Scheme not configured.", scheme, defaultFS);
		} catch (Exception e) {
			fail(e.getMessage());
		} finally {
			confFile.delete();
			tmpDir.delete();
		}
	}

	@Test
	public void testNetworkInterfaceSelection() throws Exception {
		ServerSocket server;
		String hostname = "localhost";

		try {
			InetAddress localhostAddress = InetAddress.getByName(hostname);
			server = new ServerSocket(0, 50, localhostAddress);
		} catch (IOException e) {
			// may happen in certain test setups, skip test.
			System.err.println("Skipping 'testNetworkInterfaceSelection' test.");
			return;
		}

		// open a server port to allow the system to connect
		Configuration config = new Configuration();

		config.setString(JobManagerOptions.ADDRESS, hostname);
		config.setInteger(JobManagerOptions.PORT, server.getLocalPort());

		HighAvailabilityServices highAvailabilityServices = HighAvailabilityServicesUtils.createHighAvailabilityServices(
			config,
			Executors.directExecutor(),
			HighAvailabilityServicesUtils.AddressResolution.NO_ADDRESS_RESOLUTION);

		try {
			assertNotNull(TaskManager.selectNetworkInterfaceAndPortRange(config, highAvailabilityServices)._1());
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		finally {
			highAvailabilityServices.closeAndCleanupAllData();

			try {
				server.close();
			} catch (IOException e) {
				// ignore shutdown errors
			}
		}
	}

	private File getTmpDir() {
		File tmpDir = new File(CommonTestUtils.getTempDir(), UUID.randomUUID().toString());
		assertTrue("could not create temp directory", tmpDir.mkdirs());
		return tmpDir;
	}
}
