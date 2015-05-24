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

import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 插件Manifest信息提取类，除提取Manifest信息外，也包括提取插件apk中lib的类库）
 * @author HouKangxi
 * 
 */
class PluginManifestUtil {

	/**
	 * 设置跟Manifest有关的信息
	 * @param context MainActivity的实例
	 * @param apkPath 插件apk的路径
	 * @param info 设置了插件apk的Id、FilePath的PlugInfo
	 * @throws XmlPullParserException
	 * @throws IOException
	 */
	static void setManifestInfo(Context context, String apkPath, PlugInfo info)
			throws XmlPullParserException, IOException {
		//1.读取压缩包里面的信息,将AndroidManifest的值读取出来
		ZipFile zipFile = new ZipFile(new File(apkPath), ZipFile.OPEN_READ);
		ZipEntry manifestXmlEntry = zipFile.getEntry(XmlManifestReader.DEFAULT_XML);

		String manifestXML = XmlManifestReader.getManifestXMLFromAPK(zipFile,
				manifestXmlEntry);
		PackageInfo pkgInfo = context.getPackageManager()
				.getPackageArchiveInfo(
						apkPath,
						PackageManager.GET_ACTIVITIES
								| PackageManager.GET_RECEIVERS//
								| PackageManager.GET_PROVIDERS//
								| PackageManager.GET_META_DATA//
								| PackageManager.GET_SHARED_LIBRARY_FILES//
				// | PackageManager.GET_SERVICES//
				// | PackageManager.GET_SIGNATURES//
				);
		// Log.d("ManifestReader: setManifestInfo", "GET_SHARED_LIBRARY_FILES="
		// + pkgInfo.applicationInfo.nativeLibraryDir);
		info.setPackageInfo(pkgInfo);
		//2.将插件apk中libs目录下的所有文件复制到app_plugins/插件Id-dir-lib目录下
		File libdir = ActivityOverider.getPluginLibDir(info.getId());
		try {
			if(extractLibFile(zipFile, libdir)){
				pkgInfo.applicationInfo.nativeLibraryDir=libdir.getAbsolutePath();
			}
		} finally {
			zipFile.close();
		}
		//3.设置插件中activity、receiver、service、application等信息
		setAttrs(info, manifestXML);
	}
	private static boolean extractLibFile(ZipFile zip, File tardir)
			throws ZipException, IOException {
		
		
		String defaultArch = "armeabi";
        Map<String,List<ZipEntry>> archLibEntries = new HashMap<String, List<ZipEntry>>();
		for (Enumeration<? extends ZipEntry> e = zip.entries(); e
				.hasMoreElements();) {
			ZipEntry entry = e.nextElement();
			String name = entry.getName();
			if (name.startsWith("/")) {
				name = name.substring(1);
			}
			if (name.startsWith("lib/")) {
				if(entry.isDirectory()){
					continue;
				}
				int sp = name.indexOf('/', 4);
				String en2add;
				if (sp > 0) {
					String osArch = name.substring(4, sp);
					en2add=osArch.toLowerCase();
				} else {
					en2add=defaultArch;
				}
				List<ZipEntry> ents = archLibEntries.get(en2add);
				if (ents == null) {
					ents = new LinkedList<ZipEntry>();
					archLibEntries.put(en2add, ents);
				}
				ents.add(entry);
			}
		}
		String arch = System.getProperty("os.arch");
		List<ZipEntry> libEntries = archLibEntries.get(arch.toLowerCase());
		if (libEntries == null) {
			libEntries = archLibEntries.get(defaultArch);
		}
		boolean hasLib = false;
		if (libEntries != null) {
			hasLib = true;
			if (!tardir.exists()) {
				tardir.mkdirs();
			}
			for (ZipEntry libEntry : libEntries) {
				String ename = libEntry.getName();
				String pureName = ename.substring(ename.lastIndexOf('/') + 1);
				File target = new File(tardir, pureName);
				FileUtil.writeToFile(zip.getInputStream(libEntry), target);
			}
		}
		
		return hasLib;
	}

	/**
	 * 设置插件中activity、receiver、service、application等信息
	 * @param info 插件信息
	 * @param manifestXML 带有完整AndroidManifest信息的字符串
	 * @throws XmlPullParserException
	 * @throws IOException
	 */
	private static void setAttrs(PlugInfo info, String manifestXML)
			throws XmlPullParserException, IOException {
		XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
		factory.setNamespaceAware(true);
		XmlPullParser parser = factory.newPullParser();
		parser.setInput(new StringReader(manifestXML));
		int eventType = parser.getEventType();
		String namespaceAndroid = null;
		do {
			switch (eventType) {
			case XmlPullParser.START_DOCUMENT: {
				break;
			}
			case XmlPullParser.START_TAG: {
				String tag = parser.getName();
				if (tag.equals("manifest")) {
					namespaceAndroid = parser.getNamespace("android");
				} else if ("activity".equals(parser.getName())) {
					addActivity(info, namespaceAndroid, parser);
				} else if ("receiver".equals(parser.getName())) {
					addReceiver(info, namespaceAndroid, parser);
				} else if ("service".equals(parser.getName())) {
					addService(info, namespaceAndroid, parser);
				}else if("application".equals(parser.getName())){
					parseApplicationInfo(info, namespaceAndroid, parser);
				}
				break;
			}
			case XmlPullParser.END_TAG: {
				break;
			}
			}
			eventType = parser.next();
		} while (eventType != XmlPullParser.END_DOCUMENT);
	}

	private static void parseApplicationInfo(PlugInfo info,
			String namespace, XmlPullParser parser) throws XmlPullParserException, IOException{
		String applicationName = parser.getAttributeValue(namespace, "name");
		String packageName = info.getPackageInfo().packageName;
		ApplicationInfo applicationInfo = info.getPackageInfo().applicationInfo;
		applicationInfo.name = getName(applicationName, packageName);
	}

	private static void addActivity(PlugInfo info, String namespace,
			XmlPullParser parser) throws XmlPullParserException, IOException {
		int eventType = parser.getEventType();
		String activityName = parser.getAttributeValue(namespace, "name");
		String packageName = info.getPackageInfo().packageName;
		activityName = getName(activityName, packageName);
		ResolveInfo act = new ResolveInfo();
		act.activityInfo = info.findActivityByClassNameFromPkg(activityName);
		do {
			switch (eventType) {
			case XmlPullParser.START_TAG: {
				String tag = parser.getName();
				if ("intent-filter".equals(tag)) {
					if (act.filter == null) {
						act.filter = new IntentFilter();
					}
				} else if ("action".equals(tag)) {
					String actionName = parser.getAttributeValue(namespace,
							"name");
					act.filter.addAction(actionName);
				} else if ("category".equals(tag)) {
					String category = parser.getAttributeValue(namespace,
							"name");
					act.filter.addCategory(category);
				} else if ("data".equals(tag)) {
					// TODO parse data
				}
				break;
			}
			}
			eventType = parser.next();
		} while (!"activity".equals(parser.getName()));
		//
		info.addActivity(act);
	}

	private static void addService(PlugInfo info, String namespace,
			XmlPullParser parser) throws XmlPullParserException, IOException {
		int eventType = parser.getEventType();
		String serviceName = parser.getAttributeValue(namespace, "name");
		String packageName = info.getPackageInfo().packageName;
		serviceName = getName(serviceName, packageName);
		ResolveInfo service = new ResolveInfo();
		service.serviceInfo = info.findServiceByClassName(serviceName);
		do {
			switch (eventType) {
			case XmlPullParser.START_TAG: {
				String tag = parser.getName();
				if ("intent-filter".equals(tag)) {
					if (service.filter == null) {
						service.filter = new IntentFilter();
					}
				} else if ("action".equals(tag)) {
					String actionName = parser.getAttributeValue(namespace,
							"name");
					service.filter.addAction(actionName);
				} else if ("category".equals(tag)) {
					String category = parser.getAttributeValue(namespace,
							"name");
					service.filter.addCategory(category);
				} else if ("data".equals(tag)) {
					// TODO parse data
				}
				break;
			}
			}
			eventType = parser.next();
		} while (!"service".equals(parser.getName()));
		//
		info.addService(service);
	}

	private static void addReceiver(PlugInfo info, String namespace,
			XmlPullParser parser) throws XmlPullParserException, IOException {
		int eventType = parser.getEventType();
		String receiverName = parser.getAttributeValue(namespace, "name");
		String packageName = info.getPackageInfo().packageName;
		receiverName = getName(receiverName, packageName);
		ResolveInfo receiver = new ResolveInfo();
		// 此时的activityInfo 表示 receiverInfo
		receiver.activityInfo = info.findReceiverByClassName(receiverName);
		do {
			switch (eventType) {
			case XmlPullParser.START_TAG: {
				String tag = parser.getName();
				if ("intent-filter".equals(tag)) {
					if (receiver.filter == null) {
						receiver.filter = new IntentFilter();
					}
				} else if ("action".equals(tag)) {
					String actionName = parser.getAttributeValue(namespace,
							"name");
					receiver.filter.addAction(actionName);
				} else if ("category".equals(tag)) {
					String category = parser.getAttributeValue(namespace,
							"name");
					receiver.filter.addCategory(category);
				} else if ("data".equals(tag)) {
					// TODO parse data
				}
				break;
			}
			}
			eventType = parser.next();
		} while (!"receiver".equals(parser.getName()));
		//
		info.addReceiver(receiver);
	}

	private static String getName(String nameOrig, String pkgName) {
		if (nameOrig == null) {
			return null;
		}
		StringBuilder sb = null;
		if (nameOrig.startsWith(".")) {
			sb = new StringBuilder();
			sb.append(pkgName);
			sb.append(nameOrig);
		} else if (!nameOrig.contains(".")) {
			sb = new StringBuilder();
			sb.append(pkgName);
			sb.append('.');
			sb.append(nameOrig);
		} else {
			return nameOrig;
		}
		return sb.toString();
	}
}
