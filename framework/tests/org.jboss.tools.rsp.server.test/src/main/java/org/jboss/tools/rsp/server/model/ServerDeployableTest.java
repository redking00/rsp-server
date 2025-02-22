/*******************************************************************************
 * Copyright (c) 2018 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.rsp.server.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.tools.rsp.api.ServerManagementAPIConstants;
import org.jboss.tools.rsp.api.dao.CommandLineDetails;
import org.jboss.tools.rsp.api.dao.DeployableReference;
import org.jboss.tools.rsp.api.dao.DeployableState;
import org.jboss.tools.rsp.api.dao.ServerAttributes;
import org.jboss.tools.rsp.api.dao.ServerState;
import org.jboss.tools.rsp.api.dao.UpdateServerResponse;
import org.jboss.tools.rsp.eclipse.core.runtime.CoreException;
import org.jboss.tools.rsp.eclipse.core.runtime.IStatus;
import org.jboss.tools.rsp.eclipse.core.runtime.Status;
import org.jboss.tools.rsp.launching.memento.IMemento;
import org.jboss.tools.rsp.launching.memento.JSONMemento;
import org.jboss.tools.rsp.server.spi.model.IServerManagementModel;
import org.jboss.tools.rsp.server.spi.servertype.IServer;
import org.jboss.tools.rsp.server.spi.servertype.IServerDelegate;
import org.jboss.tools.rsp.server.spi.servertype.IServerPublishModel;
import org.jboss.tools.rsp.server.util.DataLocationSysProp;
import org.jboss.tools.rsp.server.util.TestServerDelegate;
import org.jboss.tools.rsp.server.util.TestServerUtils;
import org.jboss.tools.rsp.server.util.generation.DeploymentGeneration;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class ServerDeployableTest {

	private static final String SERVER_FILENAME = "s1";
	private static final String SERVERS_DIR = "serverdeployabletest";
	private static final String SERVER_ID = "abc123";
	private static final String SERVER_TYPE = "wonka6";
	private static final String DEPLOYMENTS_DIR = SERVERS_DIR + "_deployments";
	private static final String DEPLOYABLE_LABEL = "some.name";
	private static final String DEPLOYABLE_PATH = "/papa-smurf/in/da/house";
	private static final String WAR_FILENAME = "hello-world-war-1.0.0.war";

	private static final DataLocationSysProp dataLocation = new DataLocationSysProp();

	/* TODO: remove duplication from Server */
	private static final String MEMENTO_DEPLOYABLES = "deployables";
	private static final String MEMENTO_DEPLOYABLE = "deployable";
	private static final String MEMENTO_DEPLOYABLE_LABEL = "label";
	private static final String MEMENTO_DEPLOYABLE_PATH = "path";

	@BeforeClass
	public static void beforeClass() {
		dataLocation.backup().set("ServerDeployableTest");
	}

	@AfterClass
	public static void afterClass() {
		dataLocation.restore();
	}

	private ServerModel sm;
	private File war;
	private Path serversDir;
	private File serverFile;
	private IServer server;
	private DeployableReference deployable;

	@Before
	public void before() throws IOException {
		this.serversDir = Files.createTempDirectory(SERVERS_DIR);
		this.war = new DeploymentGeneration().createWar(WAR_FILENAME, DEPLOYMENTS_DIR);
		this.sm = new ServerModel(mock(IServerManagementModel.class));
		this.sm.addServerType(TestServerUtils.createServerType(SERVER_TYPE, TestServerDelegate::new));
		this.serverFile = TestServerUtils.createServerFile(SERVER_FILENAME,serversDir,
				TestServerUtils.getServerWithoutDeployablesString(SERVER_ID, SERVER_TYPE));
		this.sm.loadServers(serversDir.toFile());
		this.server = sm.getServer(SERVER_ID);
		this.deployable = new DeployableReference(DEPLOYABLE_LABEL, DEPLOYABLE_PATH);
	}

	@Test
	public void testCannotAddDeployable() {
		ServerModel sm = TestServerUtils.createServerModel(SERVER_FILENAME, serversDir,
				TestServerUtils.getServerWithoutDeployablesString(SERVER_ID, SERVER_TYPE),
				(IServer server) -> {
					IServerDelegate spy = spy(new TestServerDelegate(server));
					doReturn(Status.CANCEL_STATUS).when(spy).canAddDeployable(any(DeployableReference.class));
					return spy;
				},
				SERVER_TYPE
		);

		IServer server = sm.getServer(SERVER_ID);
		assertTrue(sm.getDeployables(server).isEmpty());
		DeployableReference reference = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		IStatus added = sm.addDeployable(server, reference);
		assertFalse(TestServerUtils.isOk(added));
		assertTrue(sm.getDeployables(server).isEmpty());
	}

	@Test
	public void testCannotRemoveDeployable() {
		ServerModel sm = TestServerUtils.createServerModel(
				SERVER_FILENAME, serversDir,
				TestServerUtils.getServerWithoutDeployablesString(SERVER_ID, SERVER_TYPE),
				(IServer server) -> {
					IServerDelegate spy = spy(new TestServerDelegate(server));
					doReturn(Status.CANCEL_STATUS).when(spy).canRemoveDeployable(any(DeployableReference.class));
					return spy;
				},
				SERVER_TYPE);

		IServer server = sm.getServer(SERVER_ID);
		assertTrue(sm.getDeployables(server).isEmpty());
		DeployableReference reference = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		IStatus added = sm.addDeployable(server, reference);
		assertTrue(TestServerUtils.isOk(added));
		assertEquals(1, sm.getDeployables(server).size());

		IStatus removed = sm.removeDeployable(server, reference);
		assertFalse(TestServerUtils.isOk(removed));
		assertEquals(1, sm.getDeployables(server).size());
	}

	@Test
	public void testCanAddMultipleDeployables() {
		assertTrue(sm.getDeployables(server).isEmpty());
		DeployableReference reference = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		IStatus added = sm.addDeployable(server, reference);
		assertTrue(TestServerUtils.isOk(added));
		assertNotNull(sm.getDeployables(server));
		assertEquals(1, sm.getDeployables(server).size());

		DeployableReference reference2 = new DeployableReference(DEPLOYABLE_LABEL, "/smurfette/in/da/woods");
		added = sm.addDeployable(server, reference2);
		assertTrue(TestServerUtils.isOk(added));
		assertEquals(2, sm.getDeployables(server).size());
	}

	@Test
	public void testCanNotAddSamePath() {
		assertTrue(sm.getDeployables(server).isEmpty());
		DeployableReference reference = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		IStatus added = sm.addDeployable(server, reference);
		assertTrue(TestServerUtils.isOk(added));
		assertNotNull(sm.getDeployables(server));
		assertEquals(1, sm.getDeployables(server).size());

		DeployableReference reference2 = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		added = sm.addDeployable(server, reference2);
		assertFalse(TestServerUtils.isOk(added));
		assertEquals(1, sm.getDeployables(server).size());

		reference2 = new DeployableReference("papa-smurf", war.getAbsolutePath());
		added = sm.addDeployable(server, reference2);
		assertFalse(TestServerUtils.isOk(added));
		assertEquals(1, sm.getDeployables(server).size());
	}

	@Test
	public void testCannotRemoveInexistantDeployablePath() {		
		assertTrue(sm.getDeployables(server).isEmpty());
		DeployableReference reference = new DeployableReference(DEPLOYABLE_LABEL, "/gargamel/in/da/castle");
		IStatus added = sm.addDeployable(server, reference);
		assertTrue(TestServerUtils.isOk(added));
		assertNotNull(sm.getDeployables(server));
		assertEquals(1, sm.getDeployables(server).size());

		DeployableReference reference2 = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		IStatus removed = sm.removeDeployable(server, reference2);
		assertFalse(TestServerUtils.isOk(removed));
		assertEquals(1, sm.getDeployables(server).size());

		reference2 = new DeployableReference("papa-smurf", war.getAbsolutePath());
		removed = sm.removeDeployable(server, reference2);
		assertFalse(TestServerUtils.isOk(removed));
		assertEquals(1, sm.getDeployables(server).size());
	}

	@Test
	public void testDeployablesAddRemoveNoPublish() {
		List<DeployableState> deployables = sm.getDeployables(server);
		assertNotNull(deployables);
		assertTrue(deployables.isEmpty());

		DeployableReference reference = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		IStatus added = sm.addDeployable(server, reference);
		assertTrue(TestServerUtils.isOk(added));

		deployables = sm.getDeployables(server);
		assertNotNull(deployables);
		assertEquals(1, deployables.size());

		IStatus removed = sm.removeDeployable(server, reference);
		assertTrue(TestServerUtils.isOk(removed));

		deployables = sm.getDeployables(server);
		assertNotNull(deployables);
		assertTrue(deployables.isEmpty());
	}

	@Test
	public void testWontPublishIfCannotPublish() throws CoreException {
		ServerModel sm = TestServerUtils.createServerModel(
				SERVER_FILENAME, serversDir,
				TestServerUtils.getServerWithoutDeployablesString(SERVER_ID, SERVER_TYPE),
				(IServer server) -> {
					IServerDelegate spy = spy(new TestServerDelegate(server));
					doReturn(Status.CANCEL_STATUS).when(spy).canPublish();
					return spy;
				},
				SERVER_TYPE);
		IStatus added = sm.addDeployable(server, deployable);
		assertTrue(TestServerUtils.isOk(added));

		IServer server = sm.getServer(SERVER_ID);
		IStatus published = sm.publish(server, ServerManagementAPIConstants.PUBLISH_FULL);
		assertFalse(TestServerUtils.isOk(published));
	}

	@Test
	public void testCallsPublishStartAndPublishFinish() throws CoreException {
		final List<String> startFinishInvocation = new ArrayList<>();
		ServerModel sm = TestServerUtils.createServerModel(
				SERVER_FILENAME, serversDir,
				TestServerUtils.getServerWithoutDeployablesString(SERVER_ID, SERVER_TYPE),
				(IServer server) -> new TestServerDelegate(server) {

					@Override
					protected void publishStart(int publishType) throws CoreException {
						startFinishInvocation.add("start");
					}

					@Override
					protected void publishFinish(int publishType) throws CoreException {
						startFinishInvocation.add("finish");
					}
					
					@Override
					protected void fireStateChanged(ServerState state) {
						// Do nothing
					}
				},
				SERVER_TYPE);
		IStatus added = sm.addDeployable(server, deployable);
		assertTrue(TestServerUtils.isOk(added));
		assertEquals(1, sm.getDeployables(server).size());
		
		IServer server = sm.getServer(SERVER_ID);
		IStatus published = sm.publish(server, ServerManagementAPIConstants.PUBLISH_FULL);
		assertTrue(TestServerUtils.isOk(published));
		assertEquals(2, startFinishInvocation.size());
		assertEquals("start", startFinishInvocation.get(0));
		assertEquals("finish", startFinishInvocation.get(1));
	}

	@Test
	public void testPublishes3DeployablesEvenIfOneThrows() throws CoreException {
		AtomicInteger numOfPublished = new AtomicInteger();
		ServerModel sm = TestServerUtils.createServerModel(
				SERVER_FILENAME, serversDir,
				TestServerUtils.getServerWithoutDeployablesString(SERVER_ID, SERVER_TYPE),
				(IServer server) -> new TestServerDelegate(server) {

					@Override
					protected void publishDeployable(DeployableReference reference, int publishType,
							int deployablemodulePublishType) throws CoreException {
						int deployable = numOfPublished.incrementAndGet();
						if (deployable == 2) {
							throw new CoreException(Status.CANCEL_STATUS);
						}
					}
					
				},
				SERVER_TYPE);
		sm.addDeployable(server, deployable);
		sm.addDeployable(server, new DeployableReference("gargamel", "/in/the/woods"));
		sm.addDeployable(server, new DeployableReference("azrael", "/in/the/mousehole"));
		assertEquals(3, sm.getDeployables(server).size());
		
		IServer server = sm.getServer(SERVER_ID);
		IStatus published = sm.publish(server, ServerManagementAPIConstants.PUBLISH_FULL);

		// assert num of published deployables
		assertEquals(3, numOfPublished.get());

		// assert status
		assertFalse(TestServerUtils.isOk(published));
		IStatus[] deployableStates = published.getChildren();
		// contains just the errors
		assertEquals(1, deployableStates.length);
		assertFalse(TestServerUtils.isOk(deployableStates[0]));
	}

	@Test
	public void testGetDeployableState() {
		sm.addDeployable(server, deployable);
		IServerPublishModel publishModel = server.getDelegate().getServerPublishModel();
		DeployableState state = publishModel.getDeployableState(deployable);
		assertEquals(deployable, state.getReference());
	}

	@Test
	public void testDeployablesAddSaveRemoveSave() {
		List<DeployableState> deployables = sm.getDeployables(server);
		assertNotNull(deployables);
		assertTrue(deployables.isEmpty());

		DeployableReference reference = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		IStatus added = sm.addDeployable(server, reference);
		assertNotNull(added);
		assertTrue(added.isOK());

		deployables = sm.getDeployables(server);
		assertNotNull(deployables);
		assertEquals(1, deployables.size());

		try {
			sm.saveServers();
			JSONMemento memento = JSONMemento.loadMemento(new FileInputStream(serverFile));
			IMemento[] deployablesMemento = memento.getChildren(MEMENTO_DEPLOYABLES);
			assertNotNull(deployablesMemento);
			assertEquals(1, deployablesMemento.length);
			IMemento[] deployableMemento = deployablesMemento[0].getChildren(MEMENTO_DEPLOYABLE);
			assertNotNull(deployableMemento);
			assertEquals(1, deployableMemento.length);
			assertEquals(DEPLOYABLE_LABEL, deployableMemento[0].getString(MEMENTO_DEPLOYABLE_LABEL));
			assertEquals(war.getAbsolutePath(), deployableMemento[0].getString(MEMENTO_DEPLOYABLE_PATH));
		} catch(IOException | CoreException ioe) {
			ioe.printStackTrace();
			fail();
		}
		
		IStatus removed = sm.removeDeployable(server, reference);
		assertNotNull(removed);
		assertTrue(removed.isOK());

		deployables = sm.getDeployables(server);
		assertNotNull(deployables);
		assertTrue(deployables.isEmpty());
		

		try {
			sm.saveServers();
			JSONMemento memento = JSONMemento.loadMemento(new FileInputStream(serverFile));
			IMemento[] deployablesMemento = memento.getChildren(MEMENTO_DEPLOYABLES);
			boolean dne = (deployablesMemento == null || deployablesMemento.length == 0);
			assertTrue(dne);
		} catch(IOException | CoreException ioe) {
			ioe.printStackTrace();
			fail();
		}
	}

	@Test
	public void testDeployablesLoadFromData() {
		ServerModel sm = TestServerUtils.createServerModel(
				SERVER_FILENAME, serversDir,
				TestServerUtils.getServerWithDeployablesString(SERVER_ID, SERVER_TYPE),
				TestServerUtils.createServerType(SERVER_TYPE, TestServerDelegate::new), 
				null);
		IServer server = sm.getServer(SERVER_ID);
		
		List<DeployableState> deployables = sm.getDeployables(server);
		assertNotNull(deployables);
		assertEquals(1, deployables.size());

		DeployableState ds1 = deployables.get(0);
		assertNotNull(ds1);
		assertEquals(ServerManagementAPIConstants.STATE_UNKNOWN, ds1.getState());
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN, ds1.getPublishState());
		assertEquals(DEPLOYABLE_LABEL, ds1.getReference().getLabel());
	}

	@Test
	public void testDefaultPublishImplementation() {
		DeployableReference reference = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		IStatus added = sm.addDeployable(server, reference);
		assertTrue(TestServerUtils.isOk(added));

		List<DeployableState> deployables = sm.getDeployables(server);
		assertNotNull(deployables);
		assertEquals(1, deployables.size());
		
		ServerState ss = server.getDelegate().getServerState();
		List<DeployableState> dState = ss.getDeployableStates();
		assertNotNull(dState);
		assertEquals(1, dState.size());
		DeployableState oneState = dState.get(0);
		assertNotNull(oneState);
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_ADD, oneState.getPublishState());
		assertEquals(ServerManagementAPIConstants.STATE_UNKNOWN, oneState.getState());
		
		// Now do the publish
		try {
			sm.publish(server, ServerManagementAPIConstants.PUBLISH_FULL);
		} catch(CoreException ce) {
			fail(ce.getMessage());
		}
		
		// Verify module is set to no publish required and module is started
		ss = server.getDelegate().getServerState();
		dState = ss.getDeployableStates();
		assertNotNull(dState);
		assertEquals(1, dState.size());
		oneState = dState.get(0);
		assertNotNull(oneState);
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_NONE, oneState.getPublishState());
		assertEquals(ServerManagementAPIConstants.STATE_STARTED, oneState.getState());
		
		// Test remove
		
		IStatus removed = sm.removeDeployable(server, reference);
		assertTrue(TestServerUtils.isOk(removed));
		
		ss = server.getDelegate().getServerState();
		dState = ss.getDeployableStates();
		assertNotNull(dState);
		assertEquals(1, dState.size());
		oneState = dState.get(0);
		assertNotNull(oneState);
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_REMOVE, oneState.getPublishState());
		assertEquals(ServerManagementAPIConstants.STATE_STARTED, oneState.getState());
			
		// Now do the publish
		try {
			sm.publish(server, ServerManagementAPIConstants.PUBLISH_FULL);
		} catch(CoreException ce) {
			fail(ce.getMessage());
		}
		
		ss = server.getDelegate().getServerState();
		dState = ss.getDeployableStates();
		assertNotNull(dState);
		assertEquals(0, dState.size());
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_NONE, ss.getPublishState());
	}
	
	private CountDownLatch[] startSignal1 = new CountDownLatch[1];
	private CountDownLatch[] doneSignal1 = new CountDownLatch[1];
	private CountDownLatch[] startSignal2 = new CountDownLatch[1];
	private CountDownLatch[] doneSignal2 = new CountDownLatch[1];

	@Test
	public void testDefaultPublishImplementationWithDelay() {
		ServerModel sm = TestServerUtils.createServerModel(
				SERVER_FILENAME, serversDir, 
				TestServerUtils.getServerWithoutDeployablesString(SERVER_ID, SERVER_TYPE),
				TestServerUtils.createServerType(SERVER_TYPE, TestServerDelegateWithDelay::new),
				null);
		defaultPublishImplementationWithDelayInternal(sm);
	}
	
	public void defaultPublishImplementationWithDelayInternal(ServerModel sm) {
		IServer server = sm.getServer(SERVER_ID);

		DeployableReference reference = new DeployableReference(DEPLOYABLE_LABEL, war.getAbsolutePath());
		IStatus added = sm.addDeployable(server, reference);
		assertTrue(TestServerUtils.isOk(added));

		List<DeployableState> deployables = sm.getDeployables(server);
		assertNotNull(deployables);
		assertEquals(1, deployables.size());
		
		ServerState ss = server.getDelegate().getServerState();
		List<DeployableState> dState = ss.getDeployableStates();
		assertNotNull(dState);
		assertEquals(1, dState.size());
		DeployableState oneState = dState.get(0);
		assertNotNull(oneState);
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_ADD, oneState.getPublishState());
		assertEquals(ServerManagementAPIConstants.STATE_UNKNOWN, oneState.getState());

		// And verify the server state
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_FULL, ss.getPublishState());
		
		
		// Now do the publish
		startSignal1[0] = new CountDownLatch(1);
		doneSignal1[0] = new CountDownLatch(1);
		startSignal2[0] = new CountDownLatch(1);
		doneSignal2[0] = new CountDownLatch(1);

		try {
			sm.publish(server, ServerManagementAPIConstants.PUBLISH_FULL);
		} catch(CoreException ce) {
			fail(ce.getMessage());
		}
		
		// Verify module is set to no publish required and module is started
		ss = server.getDelegate().getServerState();
		dState = ss.getDeployableStates();
		assertNotNull(dState);
		assertEquals(1, dState.size());
		oneState = dState.get(0);
		assertNotNull(oneState);
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_ADD, oneState.getPublishState());
		assertEquals(ServerManagementAPIConstants.STATE_UNKNOWN, oneState.getState());
		
		// countdown once
		startSignal1[0].countDown();
		try {
			doneSignal1[0].await();
		} catch(InterruptedException ie) {}
		
		ss = server.getDelegate().getServerState();
		dState = ss.getDeployableStates();
		assertNotNull(dState);
		assertEquals(1, dState.size());
		oneState = dState.get(0);
		assertNotNull(oneState);
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_NONE, oneState.getPublishState());
		assertEquals(ServerManagementAPIConstants.STATE_UNKNOWN, oneState.getState());
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_NONE, ss.getPublishState());

		
		// countdown once
		startSignal2[0].countDown();
		try {
			doneSignal2[0].await();
		} catch(InterruptedException ie) {}
		
		ss = server.getDelegate().getServerState();
		dState = ss.getDeployableStates();
		assertNotNull(dState);
		assertEquals(1, dState.size());
		oneState = dState.get(0);
		assertNotNull(oneState);
		assertEquals(ServerManagementAPIConstants.PUBLISH_STATE_NONE, oneState.getPublishState());
		assertEquals(ServerManagementAPIConstants.STATE_STARTED, oneState.getState());
	}
	
// This test requires a file watcher service on the rsp-server
//	@Test
//	public void testPublishModifyFile() {
//		ArrayList<ServerState> states = new ArrayList<>();
//		final Boolean[] beginTest = new Boolean[1];
//		beginTest[0] = Boolean.valueOf(false);
//		IServerModelListener l = new ServerModelListenerAdapter() {
//			@Override
//			public void serverStateChanged(ServerHandle server, ServerState state) {
//				if( beginTest[0]) {
//					try {
//						startSignal1[0].await();
//					} catch(InterruptedException ie) {}
//
//					states.add(state);
//					doneSignal1[0].countDown();
//				}
//			}
//		};
//		
//		ServerModel sm = createServerModel(TestServerDelegateWithDelay::new, getServerWithoutDeployablesString(SERVER_ID, SERVER_TYPE), l);
//		defaultPublishImplementationWithDelayInternal(sm);
//		
//		states.clear();
//
//		startSignal1[0] = new CountDownLatch(1);
//		doneSignal1[0] = new CountDownLatch(1);
//
//		long timestamp = System.currentTimeMillis();
//		beginTest[0] = true;
//		war.setLastModified(timestamp);
//		// countdown once
//		assertTrue(states.size() == 0);
//		startSignal1[0].countDown();
//		boolean waitingSucceeds = false;
//		try {
//			waitingSucceeds = doneSignal1[0].await(10, TimeUnit.SECONDS);
//		} catch(InterruptedException ie) {}
//		assertTrue(waitingSucceeds);
//		assertTrue(states.size() == 1);
//		ServerState state1 = states.get(states.size()-1);
//		assertNotNull(state1);
//		List<DeployableState> ds = state1.getDeployableStates();
//		assertNotNull(ds);
//		assertEquals(ds.size(), 1);
//		DeployableState dds = ds.get(0);
//		assertEquals(dds.getPublishState(), ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL);
//	}

	private class TestServerDelegateWithDelay extends AbstractServerDelegate {
		public TestServerDelegateWithDelay(IServer server) {
			super(server);
		}
		@Override
		public CommandLineDetails getStartLaunchCommand(String mode, ServerAttributes params) {
			return null;
		}
		@Override
		protected void publishDeployable(
				DeployableReference reference, 
				int publishRequestType, int modulePublishState) throws CoreException {
			new Thread("Test publish") {
				public void run() {
					try {
						startSignal1[0].await();
					} catch(InterruptedException ie) {}
					setDeployablePublishState2(reference, 
							ServerManagementAPIConstants.PUBLISH_STATE_NONE);
					doneSignal1[0].countDown();
					
					try {
						startSignal2[0].await();
					} catch(InterruptedException ie) {}
					setDeployableState2(reference, 
							ServerManagementAPIConstants.STATE_STARTED);
					doneSignal2[0].countDown();
				}
			}.start();
		}
		@Override
		protected void fireStateChanged(ServerState state) {
			// Do nothing
		}
		protected void setDeployablePublishState2(DeployableReference reference, int publishState) {
			setDeployablePublishState(reference, publishState);
		}

		protected void setDeployableState2(DeployableReference reference, int runState) {
			setDeployableState(reference, runState);
		}
		@Override
		public void updateServer(IServer dummyServer, UpdateServerResponse resp) {
			// Do nothing
		}

	}
}
