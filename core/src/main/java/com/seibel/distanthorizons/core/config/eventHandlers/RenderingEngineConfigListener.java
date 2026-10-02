/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.config.eventHandlers;

import com.seibel.distanthorizons.core.config.listeners.IConfigListener;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IDependencySetup;

public class RenderingEngineConfigListener implements IConfigListener
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IDependencySetup DEPENDENCY_SETUP = SingletonInjector.INSTANCE.get(IDependencySetup.class);
	
	public static final RenderingEngineConfigListener INSTANCE = new RenderingEngineConfigListener();
	
	
	
	//=============//
 	// constructor //
	//=============//
	
	/** private since we only ever need one handler at a time */
	private RenderingEngineConfigListener() { }
	
	
	
	//=================//
	// config handling //
	//=================//
	
	@Override
	public void onConfigValueSet()
	{
		RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("Config Render Engine Change", () -> 
		{
			DEPENDENCY_SETUP.setRenderingApiBindingsFromConfigAsync();
		});
	}
	
	
	
}
