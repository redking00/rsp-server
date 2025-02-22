/*******************************************************************************
 * Copyright (c) 2018 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.rsp.server.wildfly.servertype.impl;

import java.util.Arrays;

import org.jboss.tools.rsp.eclipse.core.runtime.Path;
import org.jboss.tools.rsp.launching.java.ArgsUtil;
import org.jboss.tools.rsp.server.spi.servertype.IServer;
import org.jboss.tools.rsp.server.spi.servertype.IServerDelegate;
import org.jboss.tools.rsp.server.wildfly.servertype.AbstractLauncher;
import org.jboss.tools.rsp.server.wildfly.servertype.IJBossServerAttributes;
import org.jboss.tools.rsp.server.wildfly.servertype.launch.IDefaultLaunchArguments;

public class WildFlyStartLauncher extends AbstractLauncher {

	public WildFlyStartLauncher(IServerDelegate jBossServerDelegate) {
		super(jBossServerDelegate);
	}

	@Override
	protected String getWorkingDirectory() {
		String serverHome = getDelegate().getServer().getAttribute(IJBossServerAttributes.SERVER_HOME, (String) null);
		return serverHome + "/bin";
	}

	@Override
	protected String getMainTypeName() {
		return "org.jboss.modules.Main";
	}

	@Override
	protected String[] getClasspath() {
		String serverHome = getDelegate().getServer().getAttribute(IJBossServerAttributes.SERVER_HOME, (String) null);
		String jbModules = serverHome + "/jboss-modules.jar";
		return addJreClasspathEntries(Arrays.asList(jbModules));
	}

	@Override
	protected String getVMArguments() {
		IDefaultLaunchArguments largs = getLaunchArgs();
		String ret = null;
		if( largs != null ) {
			String serverHome = getDelegate().getServer().getAttribute(IJBossServerAttributes.SERVER_HOME, (String) null);
			ret = largs.getStartDefaultVMArgs(new Path(serverHome));
			int port = getDelegate().getServer().getAttribute(
					IJBossServerAttributes.JBOSS_SERVER_PORT, (int)-1);
			if( port > 0) {
				ret = ArgsUtil.setSystemProperty(ret, "jboss.http.port", ""+port);
			}
		}
		return ret;
	}

	@Override
	protected String getProgramArguments() {
		IDefaultLaunchArguments largs = getLaunchArgs();
		String r1 = null;
		if( largs != null ) {
			String serverHome = getDelegate().getServer().getAttribute(IJBossServerAttributes.SERVER_HOME, (String) null);
			r1 = largs.getStartDefaultProgramArgs(new Path(serverHome));
			
			String host = getDelegate().getServer().getAttribute(
					IJBossServerAttributes.JBOSS_SERVER_HOST, (String)null);
			if( host != null ) {
				r1 = ArgsUtil.setArg(r1, "-b", null, host);
			}
			
			String configFile = getDelegate().getServer().getAttribute(
					IJBossServerAttributes.WILDFLY_CONFIG_FILE, 
					IJBossServerAttributes.WILDFLY_CONFIG_FILE_DEFAULT);
			r1 = ArgsUtil.setArg(r1, null, "--server-config", configFile);
		}
		return r1;
	}

	@Override
	public IServer getServer() {
		return getDelegate().getServer();
	}
}
