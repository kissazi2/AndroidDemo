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

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Plug-in Manager
 * 提供给外部初始化插件、启动Activity的插件管理类
 *
 * @author HouKangxi
 */
public class PluginManager implements FileFilter {
	private static final String tag = "plugmgr";
	
	private static final PluginManager instance = new PluginManager();

	private final Map<String, PlugInfo> pluginIdToInfoMap = new ConcurrentHashMap<String, PlugInfo>();
	private final Map<String, PlugInfo> pluginPkgToInfoMap = new ConcurrentHashMap<String, PlugInfo>();
	private Context context;
	/**
	 * plugsout.创建一个用来放从插件apk的Activity继承的子Activity的Dex文件
	 */
	private String dexOutputPath;
	private volatile boolean hasInit = false;
	/** plugins.*/
	private File dexInternalStoragePath;
	private FrameworkClassLoader frameworkClassLoader;
	private PluginActivityLifeCycleCallback pluginActivityLifeCycleCallback;
	private volatile Activity actFrom;
	
	private PluginManager() {
	}

	
	public static PluginManager getInstance(Context context) {
		if (instance.hasInit || context == null) {
			if (instance.actFrom == null && context instanceof Activity) {
				instance.actFrom = (Activity) context;
			}
			return instance;
		}
		Context ctx = context;
		if (context instanceof Activity) {
			instance.actFrom = (Activity) context;
			ctx = ((Activity) context).getApplication();
		} else if (context instanceof Service) {
			ctx = ((Service) context).getApplication();
		} else if (context instanceof Application) {
			ctx = (Application) context;
		} else {
			ctx = context.getApplicationContext();
		}
		synchronized (PluginManager.class) {
			instance.init(ctx);
		}
		return instance;
	}

	static PluginManager getInstance() {
		return instance;
	}

	public boolean startMainActivity(Context context, String pkgOrId) {
		Log.d(tag, "startMainActivity by:" + pkgOrId);
		//1.根据包名或Apk命，获取plugInfo
		PlugInfo plug = preparePlugForStartActivity(context, pkgOrId);
		if (frameworkClassLoader == null) {
			Log.e(tag, "startMainActivity: frameworkClassLoader == null!");
			return false;
		}
		if (plug.getMainActivity() == null) {
			Log.e(tag, "startMainActivity: plug.getMainActivity() == null!");
			return false;
		}
		if (plug.getMainActivity().activityInfo == null) {
			Log.e(tag,
					"startMainActivity: plug.getMainActivity().activityInfo == null!");
			return false;
		}
		//2.这里很关键，三个参数分别是插件的包名、将要加载的插件Activity的名称，这是为了后面能正确地根据
		//Activity或其他普通类是宿主的还是插件的，进行分别处理
		String className = frameworkClassLoader.newActivityClassName(
				plug.getId(), plug.getMainActivity().activityInfo.name);
		//还记得之前贴的代码中(3.1中buildPlugInfo函数)，我们提到的application中的classLoader被替换
		//成FrameworkClassloader
		context.startActivity(new Intent().setComponent(new ComponentName(
				context, className)));
		return true;
	}

	public void startActivity(Context context, Intent intent) {
		performStartActivity(context, intent);
		context.startActivity(intent);
	}

	public void startActivityForResult(Activity activity, Intent intent,
			int requestCode) {
		performStartActivity(context, intent);
		activity.startActivityForResult(intent, requestCode);
	}

	private PlugInfo preparePlugForStartActivity(Context context,
			String plugIdOrPkg) {
		PlugInfo plug = null;
		plug = getPluginByPackageName(plugIdOrPkg);
		if (plug == null) {
			plug = getPluginById(plugIdOrPkg);
		}
		if (plug == null) {
			throw new IllegalArgumentException("plug not found by:"
					+ plugIdOrPkg);
		}
		return plug;
	}

	private void performStartActivity(Context context, Intent intent) {
		checkInit();

		String plugIdOrPkg;
		String actName;
		ComponentName origComp = intent.getComponent();
		if (origComp != null) {
			plugIdOrPkg = origComp.getPackageName();
			actName = origComp.getClassName();
		} else {
			throw new IllegalArgumentException(
					"plug intent must set the ComponentName!");
		}
		PlugInfo plug = preparePlugForStartActivity(context, plugIdOrPkg);
		String className = frameworkClassLoader.newActivityClassName(
				plug.getId(), actName);
		Log.i(tag, "performStartActivity: " + actName);
		ComponentName comp = new ComponentName(context, className);
		intent.setAction(null);
		intent.setComponent(comp);
	}


	private void init(Context ctx) {
		Log.i(tag, "init()...");
		context = ctx;
		//plugsout后面会说到它的作用，这里先放过
		File optimizedDexPath = ctx.getDir("plugsout", Context.MODE_PRIVATE);
		if (!optimizedDexPath.exists()) {
			optimizedDexPath.mkdirs();
		}
		dexOutputPath = optimizedDexPath.getAbsolutePath();
		dexInternalStoragePath = context
				.getDir("plugins", Context.MODE_PRIVATE);
		//创建plugins用来存放插件apk，以及插件apk中的lib包
		dexInternalStoragePath.mkdirs();
		// 改变classLoader，以便根据是插件apk、还是宿主apk中的类进行特殊处理
		try {
			Object mPackageInfo = ReflectionUtils.getFieldValue(ctx,
					"mBase.mPackageInfo", true);
			frameworkClassLoader = new FrameworkClassLoader(
					ctx.getClassLoader());
			// set Application's classLoader to FrameworkClassLoader
			ReflectionUtils.setFieldValue(mPackageInfo, "mClassLoader",
					frameworkClassLoader, true);
		} catch (Exception e) {
			e.printStackTrace();
		}
		hasInit = true;
	}

	private void checkInit() {
		if (!hasInit) {
			throw new IllegalStateException("PluginManager has not init!");
		}
	}

	public PlugInfo getPluginById(String pluginId) {
		if (pluginId == null) {
			return null;
		}
		return pluginIdToInfoMap.get(pluginId);
	}

	public PlugInfo getPluginByPackageName(String packageName) {
		return pluginPkgToInfoMap.get(packageName);
	}

	public Collection<PlugInfo> getPlugins() {
		return pluginIdToInfoMap.values();
	}

	public void uninstallPluginById(String pluginId) {
		uninstallPlugin(pluginId, true);
	}

	public void uninstallPluginByPkg(String pkg) {
		uninstallPlugin(pkg, false);
	}

	private void uninstallPlugin(String k, boolean isId) {
		checkInit();
		PlugInfo pl = isId ? removePlugById(k) : removePlugByPkg(k);
		if (pl == null) {
			return;
		}
		if (context instanceof Application) {
			if (android.os.Build.VERSION.SDK_INT >= 14) {
				try {
					Application.class
							.getMethod(
									"unregisterComponentCallbacks",
									Class.forName("android.content.ComponentCallbacks"))
							.invoke(context, pl.getApplication());
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	private PlugInfo removePlugById(String pluginId) {
		PlugInfo pl = null;
		synchronized (this) {
			pl = pluginIdToInfoMap.remove(pluginId);
			if (pl == null) {
				return null;
			}
			pluginPkgToInfoMap.remove(pl.getPackageName());
		}
		return pl;
	}

	private PlugInfo removePlugByPkg(String pkg) {
		PlugInfo pl = null;
		synchronized (this) {
			pl = pluginPkgToInfoMap.remove(pkg);
			if (pl == null) {
				return null;
			}
			pluginIdToInfoMap.remove(pl.getId());
		}
		return pl;
	}

	/**
	 * 加载指定插件或指定目录下的所有插件
	 * <p>
	 * 都使用文件名作为Id
	 * 
	 * @param pluginSrcDirFile
	 *            - apk或apk目录
	 * @return 插件集合
	 * @throws Exception
	 */
	public Collection<PlugInfo> loadPlugin(final File pluginSrcDirFile)
			throws Exception {
		checkInit();
		if (pluginSrcDirFile == null || !pluginSrcDirFile.exists()) {
			Log.e(tag, "invalidate plugin file or Directory :"
					+ pluginSrcDirFile);
			return null;
		}
		if (pluginSrcDirFile.isFile()) {
			// 如果是文件则尝试加载单个插件，暂不检查文件类型，除apk外，以后可能支持加载其他类型文件,如jar
			PlugInfo one = loadPluginWithId(pluginSrcDirFile, null, null);
			return Collections.singletonList(one);
		}
		// clear all first
		synchronized (this) {
			pluginPkgToInfoMap.clear();
			pluginIdToInfoMap.clear();
		}
		File[] pluginApks = pluginSrcDirFile.listFiles(this);
		if (pluginApks == null || pluginApks.length < 1) {
			throw new FileNotFoundException("could not find plugins in:"
					+ pluginSrcDirFile);
		}
		for (File pluginApk : pluginApks) {
			PlugInfo plugInfo = buildPlugInfo(pluginApk, null, null);
			if (plugInfo != null) {
				savePluginToMap(plugInfo);
			}
		}
		return pluginIdToInfoMap.values();
	}

	private synchronized void savePluginToMap(PlugInfo plugInfo) {
		pluginPkgToInfoMap.put(plugInfo.getPackageName(), plugInfo);
		pluginIdToInfoMap.put(plugInfo.getId(), plugInfo);
	}

	// /**
	// * 单独加载一个apk <br/>
	// * 使用文件名作为插件id <br/>
	// * 目标文件也是与源文件同名
	// *
	// * @param pluginApk
	// * @return
	// * @throws Exception
	// */
	// public PlugInfo loadPlugin(File pluginApk) throws Exception {
	// return loadPluginWithId(pluginApk, null, null);
	// }

	/**
	 * 单独加载一个apk
	 * 
	 * @param pluginApk
	 * @param pluginId
	 *            - 如果参数为null,则使用文件名作为插件id
	 * @return
	 * @throws Exception
	 */
	public PlugInfo loadPluginWithId(File pluginApk, String pluginId)
			throws Exception {
		return loadPluginWithId(pluginApk, pluginId, null);
	}

	public PlugInfo loadPluginWithId(File pluginApk, String pluginId,
			String targetFileName) throws Exception {
		checkInit();
		PlugInfo plugInfo = buildPlugInfo(pluginApk, pluginId, targetFileName);
		if (plugInfo != null) {
			savePluginToMap(plugInfo);
		}
		return plugInfo;
	}

	/**
	 *
	 * @param pluginApk 要加载的APK路径
	 * @param pluginId 插件apk的名字(一般是xxx.apk),nullable
	 * @param targetFileName 插件apk的名称，nullable
	 * @return
	 * @throws Exception
	 */
	private PlugInfo buildPlugInfo(File pluginApk, String pluginId,
			String targetFileName) throws Exception {
		PlugInfo info = new PlugInfo();
		info.setId(pluginId == null ? pluginApk.getName() : pluginId);

		File privateFile = new File(dexInternalStoragePath,
				targetFileName == null ? pluginApk.getName() : targetFileName);

		info.setFilePath(privateFile.getAbsolutePath());
		//1.把插件apk复制到app_plugins目录下，防止因为意外情况被破坏
		if (!pluginApk.getAbsolutePath().equals(privateFile.getAbsolutePath())) {
			copyApkToPrivatePath(pluginApk, privateFile);
		}
		//2.读取AndroidManifest中的信息
		String dexPath = privateFile.getAbsolutePath();
		PluginManifestUtil.setManifestInfo(context, dexPath, info);

		//3.设置插件加载器
		PluginClassLoader loader = new PluginClassLoader(dexPath,
				dexOutputPath, frameworkClassLoader, info);
		info.setClassLoader(loader);
		//4.重定向Resource对象的资源指向
		try {
			AssetManager am = (AssetManager) AssetManager.class.newInstance();
			am.getClass().getMethod("addAssetPath", String.class)
					.invoke(am, dexPath);
			info.setAssetManager(am);
			Resources ctxres = context.getResources();
			Resources res = new Resources(am, ctxres.getDisplayMetrics(),
					ctxres.getConfiguration());
			info.setResources(res);
		} catch (Exception e) {
			e.printStackTrace();
		}
		//5.初始化插件的application，调用它的onCreate()
		if (actFrom != null) {
			initPluginApplication(info, actFrom, true);
		}
		// createPluginActivityProxyDexes(info);
		Log.i(tag, "buildPlugInfo: " + info);
		return info;
	}

	// private void createPluginActivityProxyDexes(PlugInfo plugin) {
	// {
	// ActInfo act = plugin.getApplicationInfo().getMainActivity();
	// if (act != null) {
	// ActivityOverider.createProxyDex(plugin, act.name, false);
	// }
	// }
	// if (plugin.getApplicationInfo().getOtherActivities() != null) {
	// for (ActInfo act : plugin.getApplicationInfo().getOtherActivities())
	// {
	// ActivityOverider.createProxyDex(plugin, act.name, false);
	// }
	// }
	// }
	void initPluginApplication(final PlugInfo info, Activity actFrom)
			throws Exception {
		initPluginApplication(info, actFrom, false);
	}

	private void initPluginApplication(final PlugInfo plugin, Activity actFrom, boolean onLoad) throws Exception {
		if (!onLoad && plugin.getApplication() != null) {
			return;
		}
		final String className = plugin.getPackageInfo().applicationInfo.name;
		if (className == null) {
			if (onLoad) {
				return;
			}
			Application application = new Application();
			setApplicationBase(plugin, application);
			return;
		}
		Log.d(tag, plugin.getId() + ", ApplicationClassName = " + className);
		
		Runnable setApplicationTask = new Runnable() {
			public void run() {
				ClassLoader loader = plugin.getClassLoader();
				try {
					Class<?> applicationClass = loader.loadClass(className);
					Application application = (Application) applicationClass
							.newInstance();
					setApplicationBase(plugin, application);
					// invoke plugin application's onCreate()
					application.onCreate();
				} catch (Throwable e) {
					Log.e(tag, Log.getStackTraceString(e));
				}
			}
		};
		// create Application instance for plugin
		if (actFrom == null) {
			if(onLoad)
			return;
			setApplicationTask.run();
		}else{
			actFrom.runOnUiThread(setApplicationTask);
		}
	}

	private void setApplicationBase(PlugInfo plugin, Application application)
			throws Exception {
		
		synchronized (plugin) {
			if (plugin.getApplication() != null) {
				// set plugin's Application only once
				return;
			}
			plugin.setApplication(application);
			//
			PluginContextWrapper ctxWrapper = new PluginContextWrapper(context,
					plugin);
			plugin.appWrapper = ctxWrapper;
			// attach
			Method attachMethod = android.app.Application.class.getDeclaredMethod(
					"attach", Context.class);
			attachMethod.setAccessible(true);
			attachMethod.invoke(application, ctxWrapper);
			if (context instanceof Application) {
				if (android.os.Build.VERSION.SDK_INT >= 14) {
					Application.class.getMethod("registerComponentCallbacks",
							Class.forName("android.content.ComponentCallbacks"))
							.invoke(context, application);
				}
			}
			
		}
	}

	private void copyApkToPrivatePath(File pluginApk, File f) {
		// if (f.exists() && pluginApk.length() == f.length()) {
		// // 这里只是简单的判断如果两个文件长度相同则不拷贝，严格的做应该比较签名如 md5\sha-1
		// return;
		// }
		FileUtil.copyFile(pluginApk, f);
	}

	File getDexInternalStoragePath() {
		return dexInternalStoragePath;
	}

	Context getContext() {
		return context;
	}

	public PluginActivityLifeCycleCallback getPluginActivityLifeCycleCallback() {
		return pluginActivityLifeCycleCallback;
	}

	public void setPluginActivityLifeCycleCallback(
			PluginActivityLifeCycleCallback pluginActivityLifeCycleCallback) {
		this.pluginActivityLifeCycleCallback = pluginActivityLifeCycleCallback;
	}

	FrameworkClassLoader getFrameworkClassLoader() {
		return frameworkClassLoader;
	}

	@Override
	public boolean accept(File pathname) {
		if (pathname.isDirectory()) {
			return false;
		}
		String fname = pathname.getName();
		return fname.endsWith(".apk");
	}

}
