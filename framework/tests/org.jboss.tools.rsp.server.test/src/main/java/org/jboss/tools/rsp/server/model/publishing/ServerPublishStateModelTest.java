/*******************************************************************************
 * Copyright (c) 2019 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.rsp.server.model.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jboss.tools.rsp.api.ServerManagementAPIConstants;
import org.jboss.tools.rsp.api.dao.DeployableReference;
import org.jboss.tools.rsp.api.dao.DeployableState;
import org.jboss.tools.rsp.api.dao.ServerState;
import org.jboss.tools.rsp.eclipse.core.runtime.CoreException;
import org.jboss.tools.rsp.eclipse.core.runtime.IStatus;
import org.jboss.tools.rsp.server.model.AbstractServerDelegate;
import org.jboss.tools.rsp.server.model.ServerModel;
import org.jboss.tools.rsp.server.model.internal.publishing.AutoPublishThread;
import org.jboss.tools.rsp.server.model.internal.publishing.DeployableDelta;
import org.jboss.tools.rsp.server.model.internal.publishing.ServerPublishStateModel;
import org.jboss.tools.rsp.server.spi.filewatcher.FileWatcherEvent;
import org.jboss.tools.rsp.server.spi.filewatcher.IFileWatcherEventListener;
import org.jboss.tools.rsp.server.spi.filewatcher.IFileWatcherService;
import org.jboss.tools.rsp.server.spi.model.IServerManagementModel;
import org.jboss.tools.rsp.server.spi.servertype.IServerPublishModel;
import org.jboss.tools.rsp.server.util.TestServerDelegate;
import org.jboss.tools.rsp.server.util.TestServerUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class ServerPublishStateModelTest {

	private TestableServerPublishStateModel model;
	private DeployableReference deployableFile;
	private DeployableReference deployableDirectory;
	private IFileWatcherService fileWatcher;
	private DeployableReference danglingDeployable;

	@Before
	public void before() throws IOException {		
		AbstractServerDelegate delegate = mock(AbstractServerDelegate.class);
		this.fileWatcher = mock(IFileWatcherService.class);
		this.model = new TestableServerPublishStateModel(delegate, fileWatcher);

		this.deployableFile = createDeployableReference(createTempFile("deployableFile").toString());
		this.deployableDirectory = createDeployableReference(createTempDirectory("deployableDirectory").toString());
		this.danglingDeployable = createDeployableReference(createTempFile("danglingDeployable").toString());
		model.initialize(Arrays.asList(deployableFile, deployableDirectory));
	}
	
	@Test
	public void shouldInitiallyHaveUnknownServerPublishState() {
		// given
		// when
		// then
		assertThat(model.getServerPublishState()).isEqualTo(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN);
	}
	
	@Test
	public void shouldInitiallyHaveUnknownDeployablePublishState() {
		// given
		// when
		// then
		assertThat(model.getDeployableState(deployableFile).getPublishState())
			.isEqualTo(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN);
		assertThat(model.getDeployableState(deployableDirectory).getPublishState())
			.isEqualTo(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN);
	}

	@Test
	public void shouldInitiallyHaveUnknownDeployableState() {
		// given
		// when
		// then
		assertThat(model.getDeployableState(deployableFile).getState())
			.isEqualTo(ServerManagementAPIConstants.STATE_UNKNOWN);
		assertThat(model.getDeployableState(deployableDirectory).getState())
			.isEqualTo(ServerManagementAPIConstants.STATE_UNKNOWN);
	}

	@Test
	public void shouldAddRecursiveFileWatcherForDeploymentDirectory() {
		// given
		ArgumentCaptor<Path> deployablePathCaptor = ArgumentCaptor.forClass(Path.class);
		doNothing().when(fileWatcher).addFileWatcherListener(
				deployablePathCaptor.capture(), 
				any(IFileWatcherEventListener.class), 
				anyBoolean());
		// when
		// then
		verify(fileWatcher).addFileWatcherListener(
				eq(Paths.get(deployableDirectory.getPath())), 
				any(IFileWatcherEventListener.class), 
				eq(true));
	}

	@Test
	public void shouldAddNonRecursiveFileWatcherForDeploymentFile() {
		// given
		ArgumentCaptor<Path> deployablePathCaptor = ArgumentCaptor.forClass(Path.class);
		doNothing().when(fileWatcher).addFileWatcherListener(
				deployablePathCaptor.capture(), 
				any(IFileWatcherEventListener.class), 
				anyBoolean());
		// when
		// then
		verify(fileWatcher).addFileWatcherListener(
				eq(Paths.get(deployableFile.getPath())), 
				eq(model), 
				eq(false));
	}

	@Test
	public void shouldRemoveFileWatchListenerWhenRemovingADeployment() {
		// given
		ServerPublishStateModel modelSpy = fakeDeployableStates(ServerManagementAPIConstants.PUBLISH_STATE_FULL, deployableDirectory);
		// when
		modelSpy.removeDeployable(deployableDirectory);
		// then
		verify(fileWatcher).removeFileWatcherListener(Paths.get(deployableDirectory.getPath()), modelSpy);
	}

	@Test
	public void shouldContainDeployables() {
		// given
		// when
		boolean containsFile = model.contains(deployableFile);
		boolean containsDirectory = model.contains(deployableDirectory);
		boolean containsDangling = model.contains(danglingDeployable);
		// then
		assertThat(containsFile).isTrue();
		assertThat(containsDirectory).isTrue();
		assertThat(containsDangling).isFalse();
	}
	
	@Test
	public void shouldAddDeployable() {
		// given
		assertThat(model.contains(danglingDeployable)).isFalse();
		// when
		IStatus status = model.addDeployable(danglingDeployable);
		// then
		assertThat(status.isOK()).isTrue();
		assertThat(model.contains(danglingDeployable)).isTrue();
	}

	@Test
	public void shouldNotAddDeployableIfItAlreadyExists() {
		// given
		assertThat(model.contains(deployableDirectory)).isTrue();
		// when
		IStatus status = model.addDeployable(deployableDirectory);
		// then
		assertThat(status.isOK()).isFalse();
	}

	@Test
	public void shouldReturnErrorStatusIfRemoveDeployableThatIsntAdded() {
		// given
		assertThat(model.contains(danglingDeployable)).isFalse();
		// when
		IStatus status = model.removeDeployable(danglingDeployable);
		// then
		assertThat(status.getCode()).isEqualTo(IStatus.ERROR);
	}

	@Test
	public void shouldRemoveDeployableIfItsInAddState() {
		// given
		ServerPublishStateModel modelSpy = 
				fakeDeployableStates(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory);
		assertThat(modelSpy.contains(deployableDirectory)).isTrue();
		// when
		modelSpy.removeDeployable(deployableDirectory);
		// then
		assertThat(modelSpy.contains(deployableDirectory)).isFalse();
	}

	@Test
	public void shouldSetRemoveStateToDeploymentIfItsNotInAddState() {
		// given
		DeployableState deployableState = mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_FULL, deployableDirectory);
		ServerPublishStateModel modelSpy = fakeDeployableStates(deployableState);
		assertThat(modelSpy.contains(deployableDirectory)).isTrue();
		// when
		modelSpy.removeDeployable(deployableDirectory);
		// then
		assertThat(modelSpy.contains(deployableDirectory)).isTrue();
		verify(deployableState).setPublishState(ServerManagementAPIConstants.PUBLISH_STATE_REMOVE);
	}

	@Test
	public void shouldUpdateServerPublishStateWhenRemovingDeployment() {
		// given
		TestableServerPublishStateModel modelSpy = spy(model);
		// when
		modelSpy.removeDeployable(deployableDirectory);
		// then
		verify(modelSpy).setServerPublishState(anyInt(), anyBoolean());
	}

	@Test
	public void shouldSetServerPublishingStateToFullIfThereIsADeploymentInStateAdd() {
		// given
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				// will be removed
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableFile),
				// will remain
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory)
		);

		// when
		modelSpy.removeDeployable(deployableFile);
		// then
		verify(modelSpy).setServerPublishState(
				eq(ServerManagementAPIConstants.PUBLISH_STATE_FULL),
				anyBoolean());
	}

	@Test
	public void shouldSetServerPublishingStateToFullIfThereIsADeploymentInStateRemove() {
		// given
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				// will be removed
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableFile),
				// will remain
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_REMOVE, deployableDirectory)
		);
		// when
		modelSpy.removeDeployable(deployableFile);
		// then
		verify(modelSpy).setServerPublishState(
				eq(ServerManagementAPIConstants.PUBLISH_STATE_FULL),
				anyBoolean());
	}

	@Test
	public void shouldSetServerPublishingStateToFullIfThereIsADeploymentInStateFull() {
		// given
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				// will be removed
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableFile),
				// will remain
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_FULL, deployableDirectory)
		);
		// when
		modelSpy.removeDeployable(deployableFile);
		// then
		verify(modelSpy).setServerPublishState(
				eq(ServerManagementAPIConstants.PUBLISH_STATE_FULL),
				anyBoolean());
	}

	@Test
	public void shouldSetServerPublishingStateToUnknownIfThereIsADeploymentInStateUnknown() {
		// given
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				// will be removed
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory),				
				// will remain
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN, deployableFile)
		);
		// when
		modelSpy.removeDeployable(deployableDirectory);
		// then
		verify(modelSpy).setServerPublishState(
				eq(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN),
				anyBoolean());
	}

	@Test
	public void shouldSetServerPublishingStateToIncrementalIfThereIsADeploymentInStateIncremental() {
		// given
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				// will be removed
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory),				
				// will remain
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL, deployableFile)
		);
		// when
		modelSpy.removeDeployable(deployableDirectory);
		// then
		verify(modelSpy).setServerPublishState(
				eq(ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL),
				anyBoolean());
	}

	@Test
	public void shouldSetServerPublishingStateToUnknwonIfThereAreDeploymentsInStateUnknownAndIncremental() {
		// given
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				// will be removed
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory),				
				// will remain
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL, danglingDeployable),
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN, deployableFile)
		);
		// when
		modelSpy.removeDeployable(deployableDirectory);
		// then
		verify(modelSpy).setServerPublishState(
				eq(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN),
				anyBoolean());
	}

	@Test
	public void shouldSetServerPublishingStateToNoneIfThereAreNoDeployments() {
		// given
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				// will be removed
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory)
		);
		// when
		modelSpy.removeDeployable(deployableDirectory);
		// then
		verify(modelSpy).setServerPublishState(
				eq(ServerManagementAPIConstants.PUBLISH_STATE_NONE),
				anyBoolean());
	}

	@Test
	public void shouldReturnAllDeployableStates() {
		// given
		DeployableState danglingState = mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL, danglingDeployable);
		DeployableState fileState = mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN, deployableFile);
		DeployableState directoryState = mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory);
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				danglingState,
				fileState,
				directoryState
		);
		// when
		List<DeployableState> states = modelSpy.getDeployableStates();
		// then
		assertEquals(states.size(), 3);
		assertTrue(listContains(states, danglingState));
		assertTrue(listContains(states, fileState));
		assertTrue(listContains(states, directoryState));
	}

	private boolean listContains(List<DeployableState> list, DeployableState state) {
		for( DeployableState ds : list ) {
			if( ds.getPublishState() != state.getPublishState())
				continue;
			if( ds.getState() != state.getState())
				continue;
			if( !(ds.getReference().getLabel().equals(state.getReference().getLabel())))
				continue;
			if( !(ds.getReference().getPath().equals(state.getReference().getPath())))
				continue;
			return true;
		}
		return false;
	}
	
	@Test
	public void shouldReturnSpecificDeployableState() {
		// given
		DeployableState fileState = mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_UNKNOWN, deployableFile);
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL, danglingDeployable),
				fileState,
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory)
		);
		// when
		DeployableState state = modelSpy.getDeployableState(deployableFile);
		// then
		assertThat(state.getPublishState()).isEqualTo(fileState.getPublishState());
		assertThat(state.getReference().getPath()).isEqualTo(fileState.getReference().getPath());
	}

	@Test
	public void shouldReturnNullStateForInexistantDeployable() {
		// given
		ServerPublishStateModel modelSpy = fakeDeployableStates(
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL, danglingDeployable),
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory)
		);
		// when
		DeployableState state = modelSpy.getDeployableState(deployableFile);
		// then
		assertThat(state).isNull();
	}

	@Test
	public void shouldSetStateToExistingDeployableState() {
		// given
		DeployableState directoryState = mockDeployableState(
				ServerManagementAPIConstants.PUBLISH_STATE_ADD, 
				ServerManagementAPIConstants.STATE_STARTED,
				deployableDirectory);
		TestableServerPublishStateModel modelSpy = fakeDeployableStates(
				mockDeployableState(
						ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL, danglingDeployable),
				directoryState
		);
		// when
		modelSpy.setDeployableState(deployableDirectory, ServerManagementAPIConstants.STATE_STOPPED);
		// then
		String key = model.getKey(deployableDirectory);
		DeployableState state = modelSpy.getStates().get(key);
		assertThat(state).isNotNull();
		assertThat(state.getReference()).isEqualTo(directoryState.getReference());
		assertThat(state.getPublishState()).isEqualTo(directoryState.getPublishState());
		assertThat(state.getState()).isEqualTo(ServerManagementAPIConstants.STATE_STOPPED);
	}

	@Test
	public void shouldNotInsertNewStateIfDoesntExistWhenSettingState() {
		// given
		TestableServerPublishStateModel modelSpy = fakeDeployableStates();
		assertThat(modelSpy.getStates()).isEmpty();
		// when
		modelSpy.setDeployableState(danglingDeployable, ServerManagementAPIConstants.STATE_STARTED);
		// then
		assertThat(modelSpy.getStates()).isEmpty();
	}

	@Test
	public void shouldNotInsertNewStateIfDoesntExistWhenSettingPublishState() {
		// given
		TestableServerPublishStateModel modelSpy = fakeDeployableStates();
		assertThat(modelSpy.getStates()).isEmpty();
		// when
		modelSpy.setDeployablePublishState(danglingDeployable, ServerManagementAPIConstants.PUBLISH_STATE_FULL);
		// then
		assertThat(modelSpy.getStates()).isEmpty();
	}
	
	@Test
	public void shouldSetPublishStateToExistingDeployableState() {
		// given
		DeployableState directoryState = mockDeployableState(
				ServerManagementAPIConstants.PUBLISH_STATE_ADD, 
				ServerManagementAPIConstants.STATE_STARTED,
				deployableDirectory);
		TestableServerPublishStateModel modelSpy = fakeDeployableStates(
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL, deployableFile),
				directoryState
		);
		// when
		modelSpy.setDeployablePublishState(deployableDirectory, ServerManagementAPIConstants.PUBLISH_STATE_FULL);
		// then
		DeployableState state = modelSpy.getStates().get(model.getKey(deployableDirectory));
		assertThat(state).isNotNull();
		assertThat(state.getReference()).isEqualTo(directoryState.getReference());
		assertThat(state.getState()).isEqualTo(directoryState.getState());
		assertThat(state.getPublishState()).isEqualTo(ServerManagementAPIConstants.PUBLISH_STATE_FULL);
	}

	@Test
	public void shouldSetServerPublishState() {
		// given
		// when
		model.setServerPublishState(ServerManagementAPIConstants.PUBLISH_STATE_REMOVE, false);
		// then
		assertThat(model.getServerPublishState()).isEqualTo(ServerManagementAPIConstants.PUBLISH_STATE_REMOVE);
		// when
		model.setServerPublishState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, false);
		// then
		assertThat(model.getServerPublishState()).isEqualTo(ServerManagementAPIConstants.PUBLISH_STATE_NONE);
	}
	
	@Test
	public void shouldSetPublishState() throws IOException, CoreException {
		// given
		final String serverType = "firingServer";
		final String serverId = serverType + "_1";
		final Path serversDir = Files.createTempDirectory("servers");
		final String serverFilename = "firingServer";
		IServerManagementModel managementModel = mock(IServerManagementModel.class);
		ServerModel serverModel = spy(TestServerUtils.createServerModel(
				serverFilename, 
				serversDir,
				TestServerUtils.getServerWithDeployablesString(serverId, serverType),
				TestServerDelegate::new,
				serverType,
				managementModel,
				null));
		doReturn(serverModel).when(managementModel).getServerModel();
		TestServerDelegate delegate = (TestServerDelegate) serverModel.getServer(serverId).getDelegate();
		IServerPublishModel model = delegate.getServerPublishModel();

		// when
		model.setServerPublishState(ServerManagementAPIConstants.PUBLISH_STATE_REMOVE, true);

		// then
		ArgumentCaptor<ServerState> stateCaptor = ArgumentCaptor.forClass(ServerState.class);
		verify(serverModel).fireServerStateChanged(any(), stateCaptor.capture());
		assertThat(stateCaptor.getValue().getPublishState())
			.isEqualTo(ServerManagementAPIConstants.PUBLISH_STATE_REMOVE);
	}

	@Test
	public void shouldNotSetDeployablePublishStateIfItHasntNONEPublishState() throws IOException, CoreException {
		// given
		DeployableState deployableDirectoryState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_ADD, deployableDirectory);
		DeployableState deployableFileState = mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, deployableFile);
		DeployableState danglingDeployableState = mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, danglingDeployable);
		TestableServerPublishStateModel modelSpy = fakeDeployableStates(
				deployableFileState,
				deployableDirectoryState,
				danglingDeployableState);
		// when
		modelSpy.fileChanged(new FileWatcherEvent(Paths.get(deployableDirectory.getPath()), StandardWatchEventKinds.ENTRY_MODIFY));
		
		// then
		verify(deployableDirectoryState, never()).setPublishState(anyInt());
		verify(deployableFileState, never()).setPublishState(anyInt());
		verify(danglingDeployableState, never()).setPublishState(anyInt());
	}
	
	@Test
	public void shouldSetDeployablePublishStateToINCREMENTALIfItHasChangedAndHasStateNONE() throws IOException, CoreException {
		// given
		DeployableState deployableDirectoryState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, deployableDirectory);
		DeployableState danglingDeployableState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, danglingDeployable);
		DeployableState deployableFileState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, deployableFile);
		TestableServerPublishStateModel modelSpy = fakeDeployableStates(
				deployableFileState,
				deployableDirectoryState,
				danglingDeployableState);
		// when
		modelSpy.fileChanged(new FileWatcherEvent(Paths.get(deployableDirectory.getPath()), StandardWatchEventKinds.ENTRY_MODIFY));
		
		// then
		verify(deployableDirectoryState, times(1)).setPublishState(ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL);
		verify(deployableFileState, never()).setPublishState(anyInt());
		verify(danglingDeployableState, never()).setPublishState(anyInt());
	}

	@Test
	public void shouldRegisterDeltaIfDeploymentFileChanged() throws IOException, CoreException {
		// given
		DeployableState deployableDirectoryState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, deployableDirectory);
		DeployableState danglingDeployableState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, danglingDeployable);
		DeployableState deployableFileState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, deployableFile);
		TestableServerPublishStateModel modelSpy = fakeDeployableStates(
				deployableFileState,
				deployableDirectoryState,
				danglingDeployableState);
		assertThat(modelSpy.getDeltas()).isEmpty();

		// when
		modelSpy.fileChanged(new FileWatcherEvent(
				Paths.get(deployableDirectory.getPath()), 
				StandardWatchEventKinds.ENTRY_MODIFY));

		// then
		assertThat(modelSpy.getDeltas()).hasSize(1);
		DeployableDelta delta = modelSpy.getDeltas().get(modelSpy.getKey(deployableDirectory));
		assertThat(delta).isNotNull();
		assertThat(delta.getReference()).isEqualToComparingFieldByField(deployableDirectory);
	}

	@Test
	public void shouldAlterExistingDeltaIfSameDeploymentChanged() throws IOException, CoreException {
		// given
		DeployableState deployableDirectoryState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, deployableDirectory);
		DeployableState danglingDeployableState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, danglingDeployable);
		DeployableState deployableFileState = 
				mockDeployableState(ServerManagementAPIConstants.PUBLISH_STATE_NONE, deployableFile);
		TestableServerPublishStateModel modelSpy = fakeDeployableStates(
				deployableFileState,
				deployableDirectoryState,
				danglingDeployableState);
		assertThat(modelSpy.getDeltas()).isEmpty();

		// when 1st change
		modelSpy.fileChanged(new FileWatcherEvent(
				Paths.get(deployableDirectory.getPath()), 
				StandardWatchEventKinds.ENTRY_MODIFY));
		assertThat(modelSpy.getDeltas()).hasSize(1);
		DeployableDelta delta = modelSpy.getDeltas().get(modelSpy.getKey(deployableDirectory));
		assertThat(delta).isNotNull();
		assertThat(delta.getReference()).isEqualToComparingFieldByField(deployableDirectory);
		
		// when 2nd change
		modelSpy.fileChanged(new FileWatcherEvent(
				Paths.get(deployableDirectory.getPath(), "batman"), 
				StandardWatchEventKinds.ENTRY_CREATE));

		// then
		assertThat(modelSpy.getDeltas()).hasSize(1);
		delta = modelSpy.getDeltas().get(modelSpy.getKey(deployableDirectory));
		assertThat(delta).isNotNull();
		assertThat(delta.getReference()).isEqualTo(deployableDirectory);
	}

	
	@Test
	public void testOrphanedModelObject() {
		AbstractServerDelegate delegate = mock(AbstractServerDelegate.class);
		this.model = new TestableServerPublishStateModel(delegate, null);
		
		// Create a new ref
		String originalLabelAndPath = deployableFile.getLabel();
		DeployableReference ref = new DeployableReference(originalLabelAndPath,originalLabelAndPath);
		// Add it to the model
		model.addDeployable(ref);
		
		// Now change it!  
		ref.setLabel("BLAHBLAH");
		ref.setPath(deployableDirectory.getPath());
		
		// Now check the ACTUAL model and verify the model still has 
		// the original values that were added to the model directly
		Map<String, DeployableState> inModel = model.getStates();
		assertFalse(inModel.containsKey("BLAHBLAH"));
		assertTrue(inModel.size() == 1);
		List<DeployableState> vals = new ArrayList<>(inModel.values());
		assertTrue(vals.size() == 1);
		DeployableState ds = vals.get(0);
		assertFalse(ds.getReference().getLabel().equals("BLAHBLAH"));
		assertFalse(ds.getReference().getPath().equals(deployableDirectory.getPath()));
		
		
		// Now let's get a reference to that actual DeployableState via it's REAL API
		// ie not model.getStates (which is for testing only) but model.getDeployableStates()
		List<DeployableState> allStates = model.getDeployableStates();
		assertTrue(allStates.size() == 1);
		ds = allStates.get(0);
		assertNotEquals("BLAHBLAH", ds.getReference().getLabel());
		
		// Now let's try changing the details of the deployable state they gave us
		// from the REAL api, and make sure changing the returned values 
		// doesn't change the model
		ds.getReference().setLabel("BLAHBLAH");
		ds.getReference().setPath(deployableDirectory.getPath());

		// verify
		inModel = model.getStates();
		assertFalse(inModel.containsKey("BLAHBLAH"));
		assertTrue(inModel.size() == 1);
		vals = new ArrayList<>(inModel.values());
		assertTrue(vals.size() == 1);
		ds = vals.get(0);
		assertFalse(ds.getReference().getLabel().equals("BLAHBLAH"));
		assertFalse(ds.getReference().getPath().equals(deployableDirectory.getPath()));
		
	}
	
	private ServerPublishStateModel fakeDeployableStates(int publishState, DeployableReference deployable) {
		DeployableState deployableState = mockDeployableState(publishState, deployable);
		return fakeDeployableStates(deployableState);
	}

	private TestableServerPublishStateModel fakeDeployableStates(DeployableState... states) {
		Map<String, DeployableState> deployableStates = Arrays.asList(states).stream()
				.collect(Collectors.toMap(
						state -> model.getKey(state.getReference()),
						state -> state));
		return fakeDeployableStates(deployableStates);
	}

	private TestableServerPublishStateModel fakeDeployableStates(Map<String, DeployableState> states) {
		TestableServerPublishStateModel modelSpy = spy(model);
		doReturn(states).when(modelSpy).getStates();
		return modelSpy;
	}

	private DeployableState mockDeployableState(int publishState, DeployableReference deployable) {
		return mockDeployableState(publishState, -1, deployable);
	}

	private DeployableState mockDeployableState(int publishState, int runState, DeployableReference deployable) {
		DeployableState state = mock(DeployableState.class);
		doReturn(publishState).when(state).getPublishState();
		doReturn(deployable).when(state).getReference();
		doReturn(runState).when(state).getState();
		return state;
	}

	private DeployableReference createDeployableReference(String path) {
		return new DeployableReference(path, path);
	}

	private File createTempFile(String prefix) throws IOException {
		return File.createTempFile(prefix, null);
	}

	private File createTempDirectory(String prefix) throws IOException {
		File tmpFile = createTempFile(prefix);
		tmpFile.delete();
		tmpFile.mkdir();
		return tmpFile;
	}

	public static class TestableServerPublishStateModel extends ServerPublishStateModel {

		public TestableServerPublishStateModel(AbstractServerDelegate delegate, IFileWatcherService fileWatcher) {
			super(delegate, fileWatcher);
		}

		@Override
		public Map<String, DeployableState> getStates() {
			return super.getStates();
		}

		@Override
		public Map<String, DeployableDelta> getDeltas() {
			return super.getDeltas();
		}

		@Override
		public String getKey(DeployableReference deployable) {
			return super.getKey(deployable);
		}
		@Override
		protected boolean isAutoPublisherEnabled() {
			return false;
		}

	}
	
	

	@Test
	public void testAddDeployableLaunchesAutoPublish() {
		AbstractServerDelegate delegate = mock(AbstractServerDelegate.class);
		this.model = new TestableServerPublishStateModelWithAutoPublisher(delegate, null);
		TestableServerPublishStateModelWithAutoPublisher model2 = (TestableServerPublishStateModelWithAutoPublisher)model;
		// Create a new ref
		String originalLabelAndPath = deployableFile.getLabel();
		DeployableReference ref = new DeployableReference(originalLabelAndPath,originalLabelAndPath);
		
		assertFalse((model2).getPublishThreadCalled());
		// Add it to the model
		model2.addDeployable(ref);
		assertTrue((model2).getPublishThreadCalled());

		// Now try to change the file
		model2.resetPublishThreadCalled();
		assertFalse((model2).getPublishThreadCalled());
		// Add it to the model
		Path deployablePath = new File(deployableFile.getPath()).toPath();
		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_MODIFY));
		assertTrue((model2).getPublishThreadCalled());

		// Now try to delete the file
		model2.resetPublishThreadCalled();
		assertFalse((model2).getPublishThreadCalled());
		// Add it to the model
		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_DELETE));
		assertTrue((model2).getPublishThreadCalled());

		// Now try to create the file
		model2.resetPublishThreadCalled();
		assertFalse((model2).getPublishThreadCalled());
		// Add it to the model
		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_CREATE));
		assertTrue((model2).getPublishThreadCalled());

		model2.resetPublishThreadCalled();
		assertFalse((model2).getPublishThreadCalled());
		// Add it to the model
		model2.removeDeployable(ref);
		assertTrue((model2).getPublishThreadCalled());
	}
	
	public class TestableServerPublishStateModelWithAutoPublisher extends TestableServerPublishStateModel {
		private boolean publishThreadCalled = false;
		public TestableServerPublishStateModelWithAutoPublisher(AbstractServerDelegate delegate, IFileWatcherService fileWatcher) {
			super(delegate, fileWatcher);
		}
		@Override
		protected boolean isAutoPublisherEnabled() {
			return true;
		}
		protected void launchOrUpdateAutopublishThreadImpl() {
			publishThreadCalled = true;
		}
		public boolean getPublishThreadCalled() {
			return publishThreadCalled;
		}

		public void resetPublishThreadCalled() {
			publishThreadCalled = false;
		}
	}

	
	

	@Test
	public void testLaunchOrUpdateAutopublishThreadImpl() {
		AbstractServerDelegate delegate = mock(AbstractServerDelegate.class);
		this.model = new TestableServerPublishStateModelWithAutoPublisher2(delegate, null);
		TestableServerPublishStateModelWithAutoPublisher2 model2 = (TestableServerPublishStateModelWithAutoPublisher2)model;
		// Create a new ref
		String originalLabelAndPath = deployableFile.getLabel();
		DeployableReference ref = new DeployableReference(originalLabelAndPath,originalLabelAndPath);
		Path deployablePath = new File(deployableFile.getPath()).toPath();

		assertEquals(0, model2.getCreateCalled());
		assertEquals(0, model2.getPublishCalled());

		// Add it to the model
		model2.addDeployable(ref);
		assertEquals(1, model2.getCreateCalled());
		assertEquals(0, model2.getPublishCalled());

		delay(600);
		assertEquals(1, model2.getCreateCalled());
		assertEquals(1, model2.getPublishCalled());

		model2.reset();
		assertEquals(0, model2.getCreateCalled());
		assertEquals(0, model2.getPublishCalled());
		

		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_DELETE));
		delay(100);
		assertEquals(1, model2.getCreateCalled());
		assertEquals(0, model2.getPublishCalled());

		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_DELETE));
		delay(100);
		assertEquals(1, model2.getCreateCalled());
		assertEquals(0, model2.getPublishCalled());
		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_DELETE));
		delay(100);
		assertEquals(1, model2.getCreateCalled());
		assertEquals(0, model2.getPublishCalled());
		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_DELETE));
		delay(100);
		assertEquals(1, model2.getCreateCalled());
		assertEquals(0, model2.getPublishCalled());
		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_DELETE));
		delay(100);
		assertEquals(1, model2.getCreateCalled());
		assertEquals(0, model2.getPublishCalled());
		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_DELETE));
		delay(100);
		assertEquals(1, model2.getCreateCalled());
		assertEquals(0, model2.getPublishCalled());
		model2.fileChanged(new FileWatcherEvent(deployablePath, StandardWatchEventKinds.ENTRY_DELETE));
		delay(400);
		assertEquals(1, model2.getCreateCalled());
		assertEquals(1, model2.getPublishCalled());
	}
	
	
	public class TestableServerPublishStateModelWithAutoPublisher2 extends TestableServerPublishStateModel {
		private int publishCalled = 0;
		private int createNewAutoPublishThreadCalled = 0;
		public TestableServerPublishStateModelWithAutoPublisher2(AbstractServerDelegate delegate, IFileWatcherService fileWatcher) {
			super(delegate, fileWatcher);
		}
		@Override
		protected boolean isAutoPublisherEnabled() {
			return true;
		}
		protected int getInactivityTimeout() {
			return 300;
		}
		protected AutoPublishThread createNewAutoPublishThread(int timeout) {
			incrementCreateCalled();
			return new AutoPublishThread(null, timeout) {
				@Override
				protected void publishImpl() {
					incrementPublishCalled();
				}
				@Override
				protected ServerState getServerState() {
					ServerState ss = new ServerState();
					ss.setPublishState(ServerManagementAPIConstants.PUBLISH_STATE_INCREMENTAL);
					ss.setState(ServerManagementAPIConstants.STATE_STARTED);
					return ss;
				}

			};
		}

		private void incrementPublishCalled() {
			publishCalled = publishCalled+1;
		}
		
		public synchronized int getPublishCalled() {
			return publishCalled;
		}
		private void incrementCreateCalled() {
			createNewAutoPublishThreadCalled = createNewAutoPublishThreadCalled+1;
		}
		
		public synchronized int getCreateCalled() {
			return createNewAutoPublishThreadCalled;
		}
		public synchronized void reset() {
			publishCalled = 0;
			createNewAutoPublishThreadCalled = 0;
		}
	}
	private void delay(int time) {
		try {
			Thread.sleep(time);
		} catch(InterruptedException ie) {
			Thread.interrupted();
		}
	}
}

