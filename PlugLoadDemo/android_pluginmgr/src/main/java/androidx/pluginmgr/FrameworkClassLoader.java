/*
 * Copyright (C) 2015 HouKx <hkx.aidream@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.pluginmgr;

import android.util.Log;

/**
 * 框架类加载器（Application 的 classLoder被替换成此类的实例）,它主要是对插件、宿主的apk进行分别的处理。
 * 
 * @author HouKangxi
 *
 */
class FrameworkClassLoader extends ClassLoader {
	private String plugId;
	/**
	 * 保存真正的Activity名称
	 */
	private String actName;

	public FrameworkClassLoader(ClassLoader parent) {
		super(parent);
	}

	/**
	 * 1.用actName记录真正要加载的插件apk中的Activity
	 * 2.返回ActivityOverider.targetClassName来指示后面的处理流程，要加载的是插件Apk中的Activity
	 * @param plugId
	 * @param actName
	 * @return
	 */
	String newActivityClassName(String plugId, String actName) {
		this.plugId = plugId;
		this.actName = actName;
		return ActivityOverider.targetClassName;
	}

	protected Class<?> loadClass(String className, boolean resolv)
			throws ClassNotFoundException {
		Log.i("cl", "loadClass: " + className);
		if (plugId != null) {
			String pluginId = plugId;

			PlugInfo plugin = PluginManager.getInstance().getPluginById(
					pluginId);
			Log.i("cl", "plugin = " + plugin);
			if (plugin != null) {
				try {
					//这里判断要加载的Activity是否插件apk中的Activity
					if (className.equals(ActivityOverider.targetClassName)) {
						// Thread.dumpStack();
						String actClassName = actName;
						//这里的Classloader是PluginClassLoader
						return plugin.getClassLoader().loadActivityClass(
								actClassName);
					} else {
						return plugin.getClassLoader().loadClass(className);
					}
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				}
			}
		}

		return super.loadClass(className, resolv);
	}
}