package com.dynatrace.onboarding.config;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.dynatrace.onboarding.OnBoardingMain;
import com.dynatrace.onboarding.dashboards.Dashboard;
import com.dynatrace.onboarding.dashboards.DashboardTemplate;
import com.dynatrace.onboarding.dashboards.LocalDashboardTemplate;
import com.dynatrace.onboarding.profiles.LocalProfileTemplate;
import com.dynatrace.onboarding.profiles.Profile;
import com.dynatrace.onboarding.profiles.ProfileTemplate;
import com.dynatrace.onboarding.variables.DefaultVariables;
import com.dynatrace.utils.Closeables;
import com.dynatrace.utils.Iterables;
import com.dynatrace.utils.TempFiles;
import com.dynatrace.variables.UnresolvedVariableException;

public class Resources {
	
	private static final Logger LOGGER =
			Logger.getLogger(Resources.class.getName());
	
	private static final String FLD_META_INF = "META-INF";

	public final File temp = createTempFolder(); 
	
	public final Map<String, File> plugins = getPlugins();
	public final Map<String, DashboardTemplate> dashboards =
			extractEmbeddedDashboardTemplates();
	public final Map<String, ProfileTemplate> profileTemplates =
			extractEmbeddedProfileTemplates();
	public final Iterable<URLClassLoader> classLoaders = classLoaders();
	
	private static File createTempFolder() {
		return TempFiles.getTempFolder(Resources.class.getSimpleName());
	}
	
	private Iterable<URLClassLoader> classLoaders() {
		Map<String, File> files = getResources(new NameFilter() {
			@Override
			public boolean accept(String name) {
				if (!super.accept(name)) {
					return false;
				}
				String subFolder = subFolder();
				if ((subFolder != null) && !name.contains(subFolder)) {
					return false;
				}
				return name.endsWith(".jar");
			}
			
			@Override
			public String subFolder() {
				return "variables";
			}
		});
		Collection<URLClassLoader> classLoaders = new ArrayList<>();
		for (File file : files.values()) {
			try {
				classLoaders.add(
						new URLClassLoader(new URL[] { file.toURI().toURL() })
					);
			} catch (MalformedURLException e) {
				LOGGER.log(Level.FINE, "Unable to create class loader", e);
			}
		}
		return classLoaders;
	}
	
	private Map<String, File> getPlugins() {
		return getResources(new NameFilter() {
			@Override
			public boolean accept(String name) {
				if (!super.accept(name)) {
					return false;
				}
				String subFolder = subFolder();
				if ((subFolder != null) && !name.contains(subFolder)) {
					return false;
				}
				return name.endsWith(".jar");
			}
			
			@Override
			public String subFolder() {
				return "plugins";
			}
		});
	}
	
	private Map<String, DashboardTemplate> extractEmbeddedDashboardTemplates() {
		Map<String, File> templateFiles = getResources(new NameFilter() {
			@Override
			public boolean accept(String name) {
				if (!super.accept(name)) {
					return false;
				}
				String subFolder = subFolder();
				if ((subFolder != null) && !name.contains(subFolder)) {
					return false;
				}
				return name.endsWith(".dashboard.xml");
			}
			
			@Override
			public String subFolder() {
				return "dashboards";
			}
			
			@Override
			public String hashName(String name) {
				if (name == null) {
					return name;
				}
				if (name.endsWith(".dashboard.xml")) {
					return name.substring(0, name.length() - ".dashboard.xml".length());
				}
				return super.hashName(name);
			}
		});
		if (Iterables.isNullOrEmpty(templateFiles)) {
			return new HashMap<>();
		}
		Map<String, DashboardTemplate> templates = new HashMap<>();
		for (String templateName : templateFiles.keySet()) {
			if (templateName == null) {
				continue;
			}
			File templateFile = templateFiles.get(templateName);
			if (templateFile == null) {
				continue;
			}
			try {
				LocalDashboardTemplate template = new LocalDashboardTemplate(
					templateFile,
					UUID.randomUUID().toString()
				);
				templates.put(templateName, template);
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Template File '" + templateFile.getName() + "' does not contain a valid Dashboard - ignoring this resource");
			}
		}
		return templates;
	}
	
	private Map<String, ProfileTemplate> extractEmbeddedProfileTemplates() {
		Map<String, File> templateFiles = getResources(new NameFilter() {
			@Override
			public boolean accept(String name) {
				if (!super.accept(name)) {
					return false;
				}
				String subFolder = subFolder();
				if ((subFolder != null) && !name.contains(subFolder)) {
					return false;
				}
				return name.endsWith(".profile.xml");
			}
			
			@Override
			public String subFolder() {
				return "profiles";
			}
			
			@Override
			public String hashName(String name) {
				if (name == null) {
					return name;
				}
				if (name.endsWith(".profile.xml")) {
					return name.substring(0, name.length() - ".profile.xml".length());
				}
				return super.hashName(name);
			}			
		});
		if (Iterables.isNullOrEmpty(templateFiles)) {
			return new HashMap<>();
		}
		Map<String, ProfileTemplate> templates = new HashMap<>();
		for (String templateName : templateFiles.keySet()) {
			if (templateName == null) {
				continue;
			}
			File templateFile = templateFiles.get(templateName);
			if (templateFile == null) {
				continue;
			}
			try {
				ProfileTemplate template = new LocalProfileTemplate(templateFile);
				templates.put(templateName, template);
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Template File '" + templateFile.getName() + "' does not contain a valid System Profile - ignoring this resource");
			}
		}
		return templates;
	}
	
	
	private static class NameFilter {
		public boolean accept(String name) {
			return name != null;
		}
		
		public String subFolder() {
			return null;
		}
		
		public String hashName(String name) {
			return name;
		}
	}

	private Map<String, File> getResources(NameFilter filter) {
		ClassLoader classLoader = OnBoardingMain.class.getClassLoader();
		Enumeration<URL> en = null;
		String folder = "resources/" + filter.subFolder();
		try {
			en = classLoader.getResources(folder);
		} catch (IOException e) {
			LOGGER.log(
				Level.WARNING,
				"Unable to query for folder '" + folder + "' - no embedded resources will be available",
				e
			);
			return Collections.emptyMap();
		}
		if (!en.hasMoreElements()) {
			try {
				en = classLoader.getResources(FLD_META_INF);
			} catch (IOException e) {
				LOGGER.log(
					Level.WARNING,
					"Unable to query for folder '" + FLD_META_INF + "' - no embedded resources will be available",
					e
				);
				return Collections.emptyMap();
			}
		}
		Map<String, File> resources = new HashMap<>();
	    while (en.hasMoreElements()) {
	        URL url = en.nextElement();
	        URLConnection conn = null;
	        try {
				conn = url.openConnection();
			} catch (IOException e) {
				LOGGER.log(
					Level.WARNING,
					"Unable to access URL '" + url + "' - no embedded resources will be available",
					e
				);
				return Collections.emptyMap();
			}
	        if (conn instanceof JarURLConnection) {
	        	resources.putAll(
	        		extractResources((JarURLConnection) conn, filter)
	        	);
	        } else if (conn instanceof URLConnection) {
	        	resources.putAll(
	        		getResources((URLConnection) conn, filter)
	        	);
	        }
	    }
	    return resources;
	}
	
	private Map<String, File> extractResources(JarURLConnection conn, NameFilter filter) {
		Map<String, File> resources = new HashMap<>();
        try (JarFile jar = conn.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
            	JarEntry jarEntry = entries.nextElement();
                String name = jarEntry.getName();
                if (filter.accept(name)) {
                	URL url = getClass().getClassLoader().getResource(name);
                	if (url != null) {
                		LOGGER.log(Level.FINE, "Embedded Resource " + filter.hashName(name) + " discovered.");
                		int idx = name.lastIndexOf('/');
                		if (idx >= 0) {
                			name = name.substring(idx + 1);
                		}
                		File folder = new File(temp, filter.subFolder());
                		if (!folder.exists()) {
                			folder.mkdirs();
                		}
                		;
                		File resourceFile = new File(
                			folder,
                			Closeables.getFilename(url)
                		);
            			Closeables.copy(url, resourceFile);
            			resourceFile.deleteOnExit();
                    	resources.put(filter.hashName(name), resourceFile);
                	}
                }
            }
        } catch (IOException e) {
        	LOGGER.log(
        		Level.WARNING,
        		"Unable to access the JAR File of URL " + conn.getURL() + " - these entries won't get evaluated",
        		e
        	);
        }
        return resources;
	}
	
	private Map<String, File> getResources(URLConnection conn, NameFilter filter) {
		URI uri = null;
		URL url = conn.getURL();
		try {
			uri = url.toURI();
		} catch (URISyntaxException e) {
			throw new InternalError(e.getMessage());
		}
		
		File fldMETAINF = new File(uri);
		File fldRoot = fldMETAINF.getParentFile();
		File fldPlugins = fldRoot;
		String subFolder = filter.subFolder();
		if (subFolder != null) {
			fldPlugins = new File(fldRoot, subFolder);
		}
		return getResources(fldPlugins, filter);
	}
	
	private Map<String, File> getResources(File folder, NameFilter filter) {
		if (folder == null) {
			return Collections.emptyMap();
		}
		if (!folder.exists()) {
			return Collections.emptyMap();
		}
		if (!folder.isDirectory()) {
			return Collections.emptyMap();
		}
		Map<String, File> resources = new HashMap<>();
		File[] files = folder.listFiles();
		for (File file : files) {
			String absolutePath = file.getAbsolutePath();
			if (!filter.accept(absolutePath)) {
				continue;
			}
    		LOGGER.log(
    			Level.FINER,
    			"Embedded Resource " + filter.hashName(file.getName()) + " discovered."
    		);
			resources.put(filter.hashName(file.getName()), file);
		}
		return resources;
	}
	
	public void publish(Dashboard...dashboards) {
		if (Iterables.isNullOrEmpty(profileTemplates)) {
			return;
		}
		DefaultVariables variables = new DefaultVariables(new Properties());
		for (Dashboard dashboard : dashboards) {
			String name = dashboard.id();
			try {
				variables.resolve(name);
			} catch (UnresolvedVariableException e) {
				DashboardTemplate dashboardTemplate = null;
				try {
					dashboardTemplate = dashboard.asTemplate();
					DashboardTemplate embeddedTemplate = this.dashboards.get(name);
					this.dashboards.put(name, dashboardTemplate);
					if (embeddedTemplate != null) {
						LOGGER.log(
							Level.INFO,
							"The embedded Dashboard Template '" + name + "' also exists on the dynaTrace Server - using the Template located on the dynaTrace Server"
						);
					} else {
						LOGGER.log(
							Level.INFO,
							"Discovered Dashboard Template '" + name + "' located on the dynaTrace Server"
						);
					}
				} catch (IOException ioe) {
					LOGGER.log(
						Level.WARNING,
						"Discovered a Dashboard Template '" + name + "' located on the dynaTrace Server, but unable to resolve it properly - ignoring this resource"
					);
				}
			}
		}
	}
	
	private static final String TPL_OVERRIDEN_FROM_SERVER = "The embedded System Profile Template '%s' also exists on the dynaTrace Server - using the Template located on the dynaTrace Server";
	private static final String TPL_DISCOVERED_ON_SERVER = "Discovered System Profile Template '%s' located on the dynaTrace Server";
	
	public void publish(Profile...profiles) throws IOException {
		if (Iterables.isNullOrEmpty(profiles)) {
			return;
		}
		DefaultVariables variables = new DefaultVariables(new Properties());
		for (Profile profile : profiles) {
			String name = profile.id();
			try {
				variables.resolve(name);
			} catch (UnresolvedVariableException e) {
				ProfileTemplate embeddedTpl = this.profileTemplates.get(name);
				this.profileTemplates.put(name, profile.asTemplate());
				if (embeddedTpl != null) {
					LOGGER.log(
						Level.INFO,
						String.format(TPL_OVERRIDEN_FROM_SERVER, name)
					);
				} else {
					LOGGER.log(
						Level.INFO,
						String.format(TPL_DISCOVERED_ON_SERVER, name)
					);
				}
			}
		}
	}
	
}
