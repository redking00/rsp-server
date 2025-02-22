/*******************************************************************************
 * Copyright (c) 2018 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.rsp.server;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jboss.tools.rsp.api.RSPClient;
import org.jboss.tools.rsp.api.RSPServer;
import org.jboss.tools.rsp.api.SocketLauncher;
import org.jboss.tools.rsp.api.dao.Attributes;
import org.jboss.tools.rsp.api.dao.ClientCapabilitiesRequest;
import org.jboss.tools.rsp.api.dao.CommandLineDetails;
import org.jboss.tools.rsp.api.dao.CreateServerResponse;
import org.jboss.tools.rsp.api.dao.DeployableState;
import org.jboss.tools.rsp.api.dao.DiscoveryPath;
import org.jboss.tools.rsp.api.dao.DownloadRuntimeDescription;
import org.jboss.tools.rsp.api.dao.DownloadSingleRuntimeRequest;
import org.jboss.tools.rsp.api.dao.GetServerJsonResponse;
import org.jboss.tools.rsp.api.dao.JobHandle;
import org.jboss.tools.rsp.api.dao.JobProgress;
import org.jboss.tools.rsp.api.dao.LaunchAttributesRequest;
import org.jboss.tools.rsp.api.dao.LaunchParameters;
import org.jboss.tools.rsp.api.dao.ListDownloadRuntimeResponse;
import org.jboss.tools.rsp.api.dao.PublishServerRequest;
import org.jboss.tools.rsp.api.dao.ServerAttributes;
import org.jboss.tools.rsp.api.dao.ServerBean;
import org.jboss.tools.rsp.api.dao.ServerCapabilitiesResponse;
import org.jboss.tools.rsp.api.dao.ServerDeployableReference;
import org.jboss.tools.rsp.api.dao.ServerHandle;
import org.jboss.tools.rsp.api.dao.ServerLaunchMode;
import org.jboss.tools.rsp.api.dao.ServerStartingAttributes;
import org.jboss.tools.rsp.api.dao.ServerState;
import org.jboss.tools.rsp.api.dao.ServerType;
import org.jboss.tools.rsp.api.dao.StartServerResponse;
import org.jboss.tools.rsp.api.dao.Status;
import org.jboss.tools.rsp.api.dao.StopServerAttributes;
import org.jboss.tools.rsp.api.dao.UpdateServerRequest;
import org.jboss.tools.rsp.api.dao.UpdateServerResponse;
import org.jboss.tools.rsp.api.dao.WorkflowResponse;
import org.jboss.tools.rsp.api.dao.util.CreateServerAttributesUtility;
import org.jboss.tools.rsp.eclipse.core.runtime.CoreException;
import org.jboss.tools.rsp.eclipse.core.runtime.IPath;
import org.jboss.tools.rsp.eclipse.core.runtime.IStatus;
import org.jboss.tools.rsp.eclipse.core.runtime.NullProgressMonitor;
import org.jboss.tools.rsp.eclipse.core.runtime.Path;
import org.jboss.tools.rsp.eclipse.osgi.util.NLS;
import org.jboss.tools.rsp.runtime.core.model.DownloadRuntime;
import org.jboss.tools.rsp.runtime.core.model.IDownloadRuntimeRunner;
import org.jboss.tools.rsp.runtime.core.model.IDownloadRuntimesProvider;
import org.jboss.tools.rsp.server.core.internal.ServerStringConstants;
import org.jboss.tools.rsp.server.discovery.serverbeans.ServerBeanLoader;
import org.jboss.tools.rsp.server.model.RemoteEventManager;
import org.jboss.tools.rsp.server.spi.client.ClientThreadLocal;
import org.jboss.tools.rsp.server.spi.jobs.IJob;
import org.jboss.tools.rsp.server.spi.model.IServerManagementModel;
import org.jboss.tools.rsp.server.spi.servertype.IServer;
import org.jboss.tools.rsp.server.spi.servertype.IServerDelegate;
import org.jboss.tools.rsp.server.spi.servertype.IServerType;
import org.jboss.tools.rsp.server.spi.util.AlphanumComparator;
import org.jboss.tools.rsp.server.spi.util.StatusConverter;

public class ServerManagementServerImpl implements RSPServer {
	
	private final List<RSPClient> clients = new CopyOnWriteArrayList<>();
	private final List<SocketLauncher<RSPClient>> launchers = new CopyOnWriteArrayList<>();
	
	private final IServerManagementModel managementModel;
	private final RemoteEventManager remoteEventManager;
	private ServerManagementServerLauncher launcher;
	
	public ServerManagementServerImpl(ServerManagementServerLauncher launcher, 
			IServerManagementModel managementModel) {
		this.launcher = launcher;
		this.managementModel = managementModel;
		this.remoteEventManager = new RemoteEventManager(this);
	}
	
	public List<RSPClient> getClients() {
		return new ArrayList<>(clients);
	}
	
	/**
	 * Connect the given client.
	 * Return a runnable which should be executed to disconnect the client.
	 * This method is called *before* the server begins actually listening to the socket.
	 * Any functionality which requires sending a jsonrequest to the client
	 * should NOT be performed in this method, and should instead be performed
	 * in clientAdded instead.
	 */
	public Runnable addClient(SocketLauncher<RSPClient> launcher) {
		this.launchers.add(launcher);
		RSPClient client = launcher.getRemoteProxy();
		this.clients.add(client);
		return () -> this.removeClient(launcher);
	}

	public void clientAdded(SocketLauncher<RSPClient> launcher) {
		this.managementModel.clientAdded(launcher.getRemoteProxy());
		this.remoteEventManager.initClientWithServerStates(launcher.getRemoteProxy());
	}
	
	protected void removeClient(SocketLauncher<RSPClient> launcher) {
		this.launchers.remove(launcher);
		this.managementModel.clientRemoved(launcher.getRemoteProxy());
		this.clients.remove(launcher.getRemoteProxy());
	}
	
	public List<SocketLauncher<RSPClient>> getActiveLaunchers() {
		return new ArrayList<>(launchers);
	}
	
	public IServerManagementModel getModel() {
		return managementModel;
	}

	/**
	 * Returns existing discovery paths.
	 */
	@Override
	public CompletableFuture<List<DiscoveryPath>> getDiscoveryPaths() {
		return CompletableFuture.completedFuture(managementModel.getDiscoveryPathModel().getPaths());
	}

	/**
	 * Adds a path to our list of discovery paths
	 */
	@Override
	public CompletableFuture<Status> addDiscoveryPath(DiscoveryPath path) {
		return createCompletableFuture(() -> addDiscoveryPathSync(path));
	}
	
	private Status addDiscoveryPathSync(DiscoveryPath path) {
		if( isEmptyDiscoveryPath(path)) 
			return invalidParameterStatus();
		String fp = path.getFilepath();
		IPath ipath = new Path(fp);
		if( !ipath.isAbsolute()) {
			return invalidParameterStatus();
		}
		boolean ret = managementModel.getDiscoveryPathModel().addPath(path);
		return booleanToStatus(ret, "Discovery path not added: " + path.getFilepath());
	}

	@Override
	public CompletableFuture<Status> removeDiscoveryPath(DiscoveryPath path) {
		return createCompletableFuture(() -> removeDiscoveryPathSync(path));
	}
	
	public Status removeDiscoveryPathSync(DiscoveryPath path) {
		if( isEmptyDiscoveryPath(path)) 
			return invalidParameterStatus();
		String fp = path.getFilepath();
		IPath ipath = new Path(fp);
		if( !ipath.isAbsolute()) {
			return invalidParameterStatus();
		}
		boolean ret = managementModel.getDiscoveryPathModel().removePath(path);
		return booleanToStatus(ret, "Discovery path not removed: " + path.getFilepath());
	}

	private boolean isEmptyDiscoveryPath(DiscoveryPath path) {
		return path == null || isEmpty(path.getFilepath());
	}

	@Override
	public CompletableFuture<List<ServerBean>> findServerBeans(DiscoveryPath path) {
		return createCompletableFuture(() -> findServerBeansSync(path));
	}

	private List<ServerBean> findServerBeansSync(DiscoveryPath path) {
		List<ServerBean> ret = new ArrayList<>();
		if( path == null || isEmpty(path.getFilepath())) {
			return ret;
		}
		
		String fp = path.getFilepath();
		IPath ipath = new Path(fp);
		if( !ipath.isAbsolute()) {
			return ret;
		}

		ServerBeanLoader loader = new ServerBeanLoader(new File(path.getFilepath()), managementModel);
		ServerBean bean = loader.getServerBean();
		if( bean != null )
			ret.add(bean);
		return ret;	
	}

	@Override
	public void shutdown() {
		final RSPClient rspc = ClientThreadLocal.getActiveClient();
		new Thread("Shutdown") {
			@Override
			public void run() {
				ClientThreadLocal.setActiveClient(rspc);
				shutdownSync();
				ClientThreadLocal.setActiveClient(null);
			}
		}.start();
	}

	private void shutdownSync() {
		managementModel.dispose();
		launcher.shutdown();
	}
	
	@Override
	public CompletableFuture<List<ServerHandle>> getServerHandles() {
		return createCompletableFuture(() -> getServerHandlesSync());
	}
	
	private List<ServerHandle> getServerHandlesSync() {
		ServerHandle[] all = managementModel.getServerModel().getServerHandles();
		return Arrays.asList(all);
	}

	@Override
	public CompletableFuture<Status> deleteServer(ServerHandle handle) {
		return createCompletableFuture(() -> deleteServerSync(handle));
	}
	
	private Status deleteServerSync(ServerHandle handle) {
		if( handle == null || isEmpty(handle.getId())) {
			return invalidParameterStatus();
		}
		IServer server = managementModel.getServerModel().getServer(handle.getId());
		boolean b = managementModel.getServerModel().removeServer(server);
		return booleanToStatus(b, "Server not removed: " + handle.getId());
	}

	@Override
	public CompletableFuture<Attributes> getRequiredAttributes(ServerType type) {
		return createCompletableFuture(() -> getRequiredAttributesSync(type));
	}
	
	private Attributes getRequiredAttributesSync(ServerType type) {
		if( type == null || isEmpty(type.getId())) {
			return null;
		}
		IServerType serverType = managementModel.getServerModel().getIServerType(type.getId());
		Attributes rspa = managementModel.getServerModel().getRequiredAttributes(serverType);
		return rspa;
	}

	@Override
	public CompletableFuture<Attributes> getOptionalAttributes(ServerType type) {
		return createCompletableFuture(() -> getOptionalAttributesSync(type));
	}

	private Attributes getOptionalAttributesSync(ServerType type) {
		if( type == null || isEmpty(type.getId())) {
			return null;
		}
		IServerType serverType = managementModel.getServerModel().getIServerType(type.getId());
		return managementModel.getServerModel().getOptionalAttributes(serverType);
	}
	
	@Override
	public CompletableFuture<List<ServerLaunchMode>> getLaunchModes(ServerType type) {
		return createCompletableFuture(() -> getLaunchModesSync(type));
	}

	private List<ServerLaunchMode> getLaunchModesSync(ServerType type) {
		if( type == null || isEmpty(type.getId()) ) {
			return null;
		}
		IServerType serverType = managementModel.getServerModel().getIServerType(type.getId());
		List<ServerLaunchMode> l = managementModel.getServerModel()
				.getLaunchModes(serverType);
		return l;
	}
	
	@Override
	public CompletableFuture<Attributes> getRequiredLaunchAttributes(LaunchAttributesRequest req) {
		return createCompletableFuture(() -> getRequiredLaunchAttributesSync(req));
	}
	private Attributes getRequiredLaunchAttributesSync(LaunchAttributesRequest req) {
		if( req == null || isEmpty(req.getServerTypeId()) || isEmpty(req.getMode())) {
			return null;
		}
		IServerType serverType = managementModel.getServerModel().getIServerType(req.getServerTypeId());
		Attributes rspa = managementModel.getServerModel().getRequiredLaunchAttributes(serverType);
		return rspa;
	}

	@Override
	public CompletableFuture<Attributes> getOptionalLaunchAttributes(LaunchAttributesRequest req) {
		return createCompletableFuture(() -> getOptionalLaunchAttributesSync(req));
	}

	private Attributes getOptionalLaunchAttributesSync(LaunchAttributesRequest req) {
		if( req == null || isEmpty(req.getServerTypeId()) || isEmpty(req.getMode())) {
			return null;
		}
		IServerType serverType = managementModel.getServerModel().getIServerType(req.getServerTypeId());
		Attributes rspa = managementModel.getServerModel().getOptionalLaunchAttributes(serverType);
		return rspa;
	}
	
	@Override
	public CompletableFuture<CreateServerResponse> createServer(ServerAttributes attr) {
		return createCompletableFuture(() -> createServerSync(attr));
	}

	private CreateServerResponse createServerSync(ServerAttributes attr) {
		if( attr == null || isEmpty(attr.getId()) || isEmpty(attr.getServerType())) {
			Status s = invalidParameterStatus();
			return new CreateServerResponse(s, null);
		}
		
		String serverType = attr.getServerType();
		String id = attr.getId();
		Map<String, Object> attributes = attr.getAttributes();
		
		return managementModel.getServerModel().createServer(serverType, id, attributes);
	}

	
	@Override
	public CompletableFuture<GetServerJsonResponse> getServerAsJson(ServerHandle sh) {
		return createCompletableFuture(() -> getServerAsJsonSync(sh));
	}

	private GetServerJsonResponse getServerAsJsonSync(ServerHandle sh) {
		IServer server = managementModel.getServerModel().getServer(sh.getId());
		GetServerJsonResponse ret = new GetServerJsonResponse();
		ret.setServerHandle(sh);
		try {
			String json = server.asJson(new NullProgressMonitor());
			ret.setServerJson(json);
			Status stat = StatusConverter.convert(org.jboss.tools.rsp.eclipse.core.runtime.Status.OK_STATUS);
			ret.setStatus(stat);
		} catch(CoreException ce) {
			ret.setStatus(StatusConverter.convert(ce.getStatus()));
		}
		return ret;
	}
	
	@Override
	public CompletableFuture<UpdateServerResponse> updateServer(UpdateServerRequest req) {
		return createCompletableFuture(() -> updateServerSync(req));
	}

	private UpdateServerResponse updateServerSync(UpdateServerRequest req) {
		return managementModel.getServerModel().updateServer(req);
	}

	@Override
	public CompletableFuture<List<ServerType>> getServerTypes() {
		return createCompletableFuture(() -> getServerTypesSync());
	}

	private List<ServerType> getServerTypesSync() {
		ServerType[] types = managementModel.getServerModel().getAccessibleServerTypes();
		Comparator<ServerType> c = (h1,h2) -> new AlphanumComparator().compare(h1.getVisibleName(), h2.getVisibleName()); 
		return Arrays.asList(types).stream().sorted(c).collect(Collectors.toList());
	}

	@Override
	public CompletableFuture<StartServerResponse> startServerAsync(LaunchParameters attr) {
		return createCompletableFuture(() -> startServerImpl(attr));
	}

	private StartServerResponse startServerImpl(LaunchParameters attr) {
		if( attr == null || isEmpty(attr.getMode()) || isEmpty(attr.getParams().getId())) {
			Status is = errorStatus("Invalid Parameter", null);
			return (new StartServerResponse(is, null));
		}

		String id = attr.getParams().getId();
		IServer server = managementModel.getServerModel().getServer(id);
		if( server == null ) {
			String msg = NLS.bind(ServerStringConstants.SERVER_DNE, id);
			Status is = errorStatus(msg);
			return (new StartServerResponse(is, null));
		}

		IServerDelegate del = server.getDelegate();
		if( del == null ) {
			Status is = errorStatus(NLS.bind(ServerStringConstants.UNEXPECTED_ERROR_DELEGATE, id));
			return (new StartServerResponse(is, null));
		}
		
		try {
			return del.start(attr.getMode());
		} catch( Exception e ) {
			Status is = errorStatus(ServerStringConstants.UNEXPECTED_ERROR, e);
			return new StartServerResponse(is, null);
		}
	}
	
	@Override
	public CompletableFuture<Status> stopServerAsync(StopServerAttributes attr) {
		return createCompletableFuture(() -> stopServerImpl(attr));
	}

	private Status stopServerImpl(StopServerAttributes attr) {
		if( attr == null || isEmpty(attr.getId())) {
			return invalidParameterStatus();
		}

		IServer server = managementModel.getServerModel().getServer(attr.getId());
		if( server == null ) {
			String msg = NLS.bind(ServerStringConstants.SERVER_DNE, attr.getId());
			return errorStatus(msg);
		}
		IServerDelegate del = server.getDelegate();
		if( del == null ) {
			return errorStatus("An unexpected error occurred: Server " + attr.getId() + " has no delegate.");
		}
		
		if(del.getServerRunState() == IServerDelegate.STATE_STOPPED && !attr.isForce()) {
			return errorStatus(
					"The server is already marked as stopped. If you wish to force a stop request, please set the force flag to true.");
		}
		
		try {
			return StatusConverter.convert(del.stop(attr.isForce()));
		} catch( Exception e ) {
			return errorStatus(ServerStringConstants.UNEXPECTED_ERROR, e);
		}

	}

	@Override
	public CompletableFuture<CommandLineDetails> getLaunchCommand(LaunchParameters req) {
		return createCompletableFuture(() -> getLaunchCommandSync(req));
	}

	private CommandLineDetails getLaunchCommandSync(LaunchParameters req) {
		boolean empty = req == null || isEmpty(req.getMode()) || isEmpty(req.getParams().getId()); 
		if( !empty ) {
			String id = req.getParams().getId();
			IServer server = managementModel.getServerModel().getServer(id);
			if( server != null ) {
				IServerDelegate del = server.getDelegate();
				if( del != null ) {
					try {
						return del.getStartLaunchCommand(req.getMode(), req.getParams());
					} catch( Exception e ) {
						// Ignore
					}
				}
			}
		}
		return null;
	}
	
	@Override
	public CompletableFuture<ServerState> getServerState(ServerHandle handle) {
		return createCompletableFuture(() -> getServerStateSync(handle));
	}

	public ServerState getServerStateSync(ServerHandle handle) {
		IServer is = managementModel.getServerModel().getServer(handle.getId());
		return is.getDelegate().getServerState();
	}
	
	@Override
	public CompletableFuture<Status> serverStartingByClient(ServerStartingAttributes attr) {
		return createCompletableFuture(() -> serverStartingByClientSync(attr));
	}

	private Status serverStartingByClientSync(ServerStartingAttributes attr) {
		if( attr == null || attr.getRequest() == null || isEmpty(attr.getRequest().getMode())
				|| isEmpty(attr.getRequest().getParams().getId())) {
			return invalidParameterStatus();
		}
		String id = attr.getRequest().getParams().getId();
		IServer server = managementModel.getServerModel().getServer(id);
		if( server == null ) {
			String msg = NLS.bind(ServerStringConstants.SERVER_DNE, id);
			return errorStatus(msg);
		}
		IServerDelegate del = server.getDelegate();
		if( del == null ) {
			return errorStatus(NLS.bind(ServerStringConstants.UNEXPECTED_ERROR_DELEGATE, id));
		}
		try {
			return StatusConverter.convert(del.clientSetServerStarting(attr));
		} catch( Exception e ) {
			return errorStatus(ServerStringConstants.UNEXPECTED_ERROR, e);
		}
	}
	
	@Override
	public CompletableFuture<Status> serverStartedByClient(LaunchParameters attr) {
		return createCompletableFuture(() -> serverStartedByClientSync(attr));
	}

	private Status serverStartedByClientSync(LaunchParameters attr) {
		if( attr == null || attr.getParams() == null || isEmpty(attr.getParams().getId())) {
			return invalidParameterStatus();
		}

		String id = attr.getParams().getId();
		IServer server = managementModel.getServerModel().getServer(id);
		if( server == null ) {
			String msg = NLS.bind(ServerStringConstants.SERVER_DNE, id);
			return errorStatus(msg);
		}
		IServerDelegate del = server.getDelegate();
		if( del == null ) {
			return errorStatus("Server error: Server " + id + " does not have a delegate.");
		}

		try {
			return StatusConverter.convert(del.clientSetServerStarted(attr));
		} catch( Exception e ) {
			return errorStatus(ServerStringConstants.UNEXPECTED_ERROR, e);
		}
	}

	@Override
	public CompletableFuture<ServerCapabilitiesResponse> registerClientCapabilities(ClientCapabilitiesRequest request) {
		RSPClient rspc = ClientThreadLocal.getActiveClient();
		IStatus s = managementModel.getCapabilityManagement().registerClientCapabilities(rspc, request);
		Status st = StatusConverter.convert(s);
		Map<String,String> resp2 = managementModel.getCapabilityManagement().getServerCapabilities();
		ServerCapabilitiesResponse resp = new ServerCapabilitiesResponse(st, resp2);
		return CompletableFuture.completedFuture(resp);
	}

	/*
	 * Utility methods below
	 */	
	private Status booleanToStatus(boolean b, String message) {
		IStatus s = null;
		if( b ) {
			s = org.jboss.tools.rsp.eclipse.core.runtime.Status.OK_STATUS;
		} else {
			s = new org.jboss.tools.rsp.eclipse.core.runtime.Status(
					IStatus.ERROR, ServerCoreActivator.BUNDLE_ID, message);
		}
		return StatusConverter.convert(s);
	}

	private boolean isEmpty(String s) {
		return s == null || s.isEmpty();
	}

	private Status invalidParameterStatus() {
		IStatus s = new org.jboss.tools.rsp.eclipse.core.runtime.Status(
				IStatus.ERROR, ServerCoreActivator.BUNDLE_ID, "Parameter is invalid. It may be null, missing required fields, or unacceptable values.");
		return StatusConverter.convert(s);
	}

	@Override
	public CompletableFuture<List<DeployableState>> getDeployables(ServerHandle handle) {
		return createCompletableFuture(() -> getDeployablesSync(handle));
	}

	// This API has no way to return an error. Should be changed
	public List<DeployableState> getDeployablesSync(ServerHandle handle) {
		if( handle == null || handle.getId() == null ) {
			return new ArrayList<DeployableState>();
		}
		IServer server = managementModel.getServerModel().getServer(handle.getId());
		if( server == null ) {
			return new ArrayList<DeployableState>();
		}
		return managementModel.getServerModel().getDeployables(server);
	}
	
	public CompletableFuture<Attributes> listDeploymentOptions(ServerHandle handle) {
		return createCompletableFuture(() -> listDeploymentOptionsSync(handle));
	}
	
	// This API has no way to return an error. Should be changed
	public Attributes listDeploymentOptionsSync(ServerHandle handle) {
		if( handle == null ) {
			return new CreateServerAttributesUtility().toPojo();
		}
		IServer server = managementModel.getServerModel().getServer(handle.getId());
		if( server == null || server.getDelegate() == null) {
			return new CreateServerAttributesUtility().toPojo();
		}
		return server.getDelegate().listDeploymentOptions();
	}
	
	public CompletableFuture<Status> addDeployable(ServerDeployableReference request) {
		return createCompletableFuture(() -> addDeployableSync(request.getServer(), request));
	}

	public Status addDeployableSync(ServerHandle handle, ServerDeployableReference req) {
		if( req == null || req.getServer() == null || req.getDeployableReference() == null) {
			return errorStatus("Invalid request; Expected fields not present.", null);
		}
		String serverId = req.getServer().getId();
		IServer server = managementModel.getServerModel().getServer(serverId);
		if( server == null ) {
			return errorStatus( "Server " + serverId + " not found.");
		}
		IStatus stat = managementModel.getServerModel().addDeployable(server, req.getDeployableReference());
		return StatusConverter.convert(stat);
	}
	
	public CompletableFuture<Status> removeDeployable(ServerDeployableReference request) {
		return createCompletableFuture(() -> removeDeployableSync(request));
	}

	public Status removeDeployableSync(ServerDeployableReference reference) {
		if( reference == null || reference.getServer() == null || reference.getDeployableReference() == null) {
			return errorStatus("Invalid request; Expected fields not present.", null);
		}
		String serverId = reference.getServer().getId();
		IServer server = managementModel.getServerModel().getServer(serverId);
		if( server == null ) {
			return errorStatus( "Server " + serverId + " not found.");
		}

		IStatus stat = managementModel.getServerModel().removeDeployable(server, reference.getDeployableReference());
		return StatusConverter.convert(stat);
	}

	@Override
	public CompletableFuture<Status> publish(PublishServerRequest request) {
		return createCompletableFuture(() -> publishSync(request));
	}

	private Status publishSync(PublishServerRequest request) {
		if( request == null || request.getServer() == null ) {
			return errorStatus("Invalid request; Expected fields not present.", null);
		}
		
		try {
			IServer server = managementModel.getServerModel().getServer(request.getServer().getId());
			IStatus stat = managementModel.getServerModel().publish(server, request.getKind());
			return StatusConverter.convert(stat);
		} catch(CoreException ce) {
			return StatusConverter.convert(ce.getStatus());
		}
	}

	private static <T> CompletableFuture<T> createCompletableFuture(Supplier<T> supplier) {
		final RSPClient rspc = ClientThreadLocal.getActiveClient();
		CompletableFuture<T> completableFuture = new CompletableFuture<>();
		CompletableFuture.runAsync(() -> {
			ClientThreadLocal.setActiveClient(rspc);
			completableFuture.complete(supplier.get());
			ClientThreadLocal.setActiveClient(null);
		});
		return completableFuture;
	}

	@Override
	public CompletableFuture<ListDownloadRuntimeResponse> listDownloadableRuntimes() {
		return createCompletableFuture(() -> listDownloadableRuntimesInternal());
	}

	private ListDownloadRuntimeResponse listDownloadableRuntimesInternal() {
		Map<String, DownloadRuntime> map = managementModel.getDownloadRuntimeModel().getOrLoadDownloadRuntimes(new NullProgressMonitor());
		AlphanumComparator comp = new AlphanumComparator();
		Comparator<DownloadRuntimeDescription> alphanumComp = (drd1,drd2) -> comp.compare(drd1.getName(), drd2.getName());
		List<DownloadRuntimeDescription> list = map.values().stream()
				.sorted(alphanumComp)
				.map(dlrt -> dlrt.toDao())
				.collect(Collectors.toList());
		ListDownloadRuntimeResponse resp = new ListDownloadRuntimeResponse();
		resp.setRuntimes(list);
		return resp;
	}

	@Override
	public CompletableFuture<WorkflowResponse> downloadRuntime(DownloadSingleRuntimeRequest req) {
		return createCompletableFuture(() -> downloadRuntimeInternal(req));
	}

	private WorkflowResponse downloadRuntimeInternal(DownloadSingleRuntimeRequest req) {
		String id = req.getDownloadRuntimeId();
		IDownloadRuntimesProvider provider = managementModel.getDownloadRuntimeModel().findProviderForRuntime(id);
		if( provider != null ) {
			DownloadRuntime dlrt = managementModel.getDownloadRuntimeModel().findDownloadRuntime(id, new NullProgressMonitor());
			IDownloadRuntimeRunner executor = provider.getDownloadRunner(dlrt);
			if( executor != null ) {
				WorkflowResponse response = executor.execute(req);
				return response;
			}
		}
		WorkflowResponse error = new WorkflowResponse();
		Status s = errorStatus("Unable to find an executor for the given download runtime", null);
		error.setStatus(s);
		error.setItems(new ArrayList<>());
		return error;
	}

	@Override
	public CompletableFuture<List<JobProgress>> getJobs() {
		return createCompletableFuture(() -> getJobsSync());
	}
	
	protected List<JobProgress> getJobsSync() {
		List<IJob> jobs = managementModel.getJobManager().getJobs();
		List<JobProgress> ret = new ArrayList<>();
		JobProgress jp = null;
		for( IJob i : jobs ) {
			jp = new JobProgress(new JobHandle(i.getName(), i.getId()), i.getProgress());
			ret.add(jp);
		}
		return ret;
	}

	@Override
	public CompletableFuture<Status> cancelJob(JobHandle job) {
		return createCompletableFuture(() -> cancelJobSync(job));
	}
	
	protected Status cancelJobSync(JobHandle job) {
		IStatus s =  managementModel.getJobManager().cancelJob(job);
		return StatusConverter.convert(s);
	}

	private Status errorStatus(String msg) {
		return errorStatus(msg, null);
	}
	private Status errorStatus(String msg, Throwable t) {
		IStatus is = new org.jboss.tools.rsp.eclipse.core.runtime.Status(IStatus.ERROR, 
				ServerCoreActivator.BUNDLE_ID, 
				msg, t);
		return StatusConverter.convert(is);
	}
}
