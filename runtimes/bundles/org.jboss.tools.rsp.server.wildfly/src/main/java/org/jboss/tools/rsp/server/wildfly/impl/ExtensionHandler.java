/*******************************************************************************
 * Copyright (c) 2018-2019 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.rsp.server.wildfly.impl;

import org.jboss.tools.rsp.server.spi.model.IServerManagementModel;
import org.jboss.tools.rsp.server.spi.servertype.IServerType;
import org.jboss.tools.rsp.server.wildfly.runtimes.download.DownloadRuntimesProvider;
import org.jboss.tools.rsp.server.wildfly.servertype.impl.WildFlyServerTypes;

public class ExtensionHandler {

	private static final IServerType[] TYPES = {
			WildFlyServerTypes.WF17_SERVER_TYPE,
			WildFlyServerTypes.WF16_SERVER_TYPE,
			WildFlyServerTypes.WF15_SERVER_TYPE,
			WildFlyServerTypes.WF14_SERVER_TYPE,
			WildFlyServerTypes.WF13_SERVER_TYPE,
			WildFlyServerTypes.WF12_SERVER_TYPE,
			WildFlyServerTypes.WF11_SERVER_TYPE,
			WildFlyServerTypes.WF10_SERVER_TYPE,
			WildFlyServerTypes.WF9_SERVER_TYPE,
			WildFlyServerTypes.WF8_SERVER_TYPE,
			WildFlyServerTypes.WF71_SERVER_TYPE,
			WildFlyServerTypes.WF7_SERVER_TYPE,
			WildFlyServerTypes.AS6_SERVER_TYPE,
			WildFlyServerTypes.AS51_SERVER_TYPE,
			WildFlyServerTypes.AS5_SERVER_TYPE,
			WildFlyServerTypes.AS42_SERVER_TYPE,
			WildFlyServerTypes.AS4_SERVER_TYPE,
			WildFlyServerTypes.AS32_SERVER_TYPE,
			
			WildFlyServerTypes.EAP43_SERVER_TYPE,
			WildFlyServerTypes.EAP50_SERVER_TYPE,
			WildFlyServerTypes.EAP60_SERVER_TYPE,
			WildFlyServerTypes.EAP61_SERVER_TYPE,
			WildFlyServerTypes.EAP70_SERVER_TYPE,
			WildFlyServerTypes.EAP71_SERVER_TYPE,
			WildFlyServerTypes.EAP72_SERVER_TYPE,
	};

	private ExtensionHandler() {
		// inhibit instantiation
	}

	private static JBossServerBeanTypeProvider beanProvider = null;
	private static DownloadRuntimesProvider dlrtProvider = null;
	public static void addExtensions(IServerManagementModel model) {
		beanProvider = new JBossServerBeanTypeProvider();
		dlrtProvider = new DownloadRuntimesProvider(model);
		model.getServerBeanTypeManager().addTypeProvider(beanProvider);
		model.getServerModel().addServerTypes(TYPES);
		model.getDownloadRuntimeModel().addDownloadRuntimeProvider(dlrtProvider);
	}
	
	public static void removeExtensions(IServerManagementModel model) {
		model.getServerBeanTypeManager().removeTypeProvider(beanProvider);
		model.getServerModel().removeServerTypes(TYPES);
		model.getDownloadRuntimeModel().removeDownloadRuntimeProvider(dlrtProvider);
	}
}