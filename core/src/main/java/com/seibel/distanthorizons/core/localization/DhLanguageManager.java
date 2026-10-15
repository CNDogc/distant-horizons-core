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

package com.seibel.distanthorizons.core.localization;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.json.JsonFormat;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.jar.JarUtils;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Manages custom language selection, dynamic discovery of language files,
 * external overrides from config/DistantHorizons/lang, and translation fallback.
 *
 * @author Dogc
 */
public class DhLanguageManager
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	public static final DhLanguageManager INSTANCE = new DhLanguageManager();

	public static final String AUTO = "auto";
	public static final String EN_US = "en_us";

	private static final String LANG_RESOURCE_PREFIX = "assets/distanthorizons/lang/";

	/** Human-readable names for standard locales */
	private static final Map<String, String> KNOWN_LANGUAGE_NAMES = new HashMap<String, String>()
	{{
		this.put(AUTO, "跟随游戏 (Follow Minecraft)");
		this.put("zh_cn", "简体中文 (Simplified Chinese)");
		this.put("zh_tw", "繁體中文 (Traditional Chinese)");
		this.put("zh_hk", "繁體中文 (香港)");
		this.put("en_us", "English (US)");
		this.put("en_gb", "English (UK)");
		this.put("en_ca", "English (Canada)");
		this.put("en_au", "English (Australia)");
		this.put("en_pt", "Pirate Speak");
		this.put("ru_ru", "Русский (Russian)");
		this.put("de_de", "Deutsch (German)");
		this.put("fr_fr", "Français (French)");
		this.put("es_es", "Español (España)");
		this.put("es_mx", "Español (México)");
		this.put("ja_jp", "日本語 (Japanese)");
		this.put("ko_kr", "한국어 (Korean)");
		this.put("pt_br", "Português (Brasil)");
		this.put("pt_pt", "Português (Portugal)");
		this.put("it_it", "Italiano (Italian)");
		this.put("pl_pl", "Polski (Polish)");
		this.put("uk_ua", "Українська (Ukrainian)");
		this.put("cs_cz", "Čeština (Czech)");
		this.put("hu_hu", "Magyar (Hungarian)");
		this.put("tr_tr", "Türkçe (Turkish)");
		this.put("nl_nl", "Nederlands (Dutch)");
		this.put("sv_se", "Svenska (Swedish)");
		this.put("vi_vn", "Tiếng Việt (Vietnamese)");
		this.put("th_th", "ไทย (Thai)");
	}};

	/** Probed candidates for fallback classpath detection */
	private static final List<String> COMMON_LANG_CODES = Arrays.asList(
		"en_us", "en_pt", "zh_cn", "zh_tw", "ru_ru", "de_de",
		"fr_fr", "es_es", "es_mx", "ja_jp", "ko_kr", "pt_br",
		"it_it", "pl_pl", "uk_ua", "cs_cz", "nl_nl"
	);

	public static class LanguageOption
	{
		public final String code;
		public final String displayName;

		public LanguageOption(String code, String displayName)
		{
			this.code = code;
			this.displayName = displayName;
		}

		@Override
		public String toString()
		{
			return this.displayName;
		}
	}

	private volatile String currentLanguage = AUTO;
	private final Map<String, String> activeTranslations = new ConcurrentHashMap<>();
	private final Map<String, String> fallbackTranslations = new ConcurrentHashMap<>();
	private final List<LanguageOption> availableLanguages = new CopyOnWriteArrayList<>();
	private volatile boolean scanned = false;

	private DhLanguageManager()
	{
	}

	public boolean isAuto()
	{
		return AUTO.equalsIgnoreCase(this.currentLanguage);
	}

	public String getCurrentLanguage()
	{
		return this.currentLanguage;
	}

	public List<LanguageOption> getAvailableLanguages()
	{
		if (!this.scanned || this.availableLanguages.isEmpty())
		{
			this.scanAvailableLanguages();
		}
		return Collections.unmodifiableList(this.availableLanguages);
	}

	public List<String> getAvailableLanguageCodes()
	{
		List<LanguageOption> options = this.getAvailableLanguages();
		List<String> codes = new ArrayList<>(options.size());
		for (LanguageOption option : options)
		{
			codes.add(option.code);
		}
		return codes;
	}

	public String getDisplayNameForCode(String code)
	{
		if (code == null)
		{
			return "";
		}
		String lower = code.toLowerCase().trim();
		if (KNOWN_LANGUAGE_NAMES.containsKey(lower))
		{
			return KNOWN_LANGUAGE_NAMES.get(lower);
		}
		try
		{
			Locale locale = Locale.forLanguageTag(lower.replace('_', '-'));
			String display = locale.getDisplayName(locale);
			if (display != null && !display.isEmpty() && !display.equalsIgnoreCase(lower))
			{
				return display + " (" + lower + ")";
			}
		}
		catch (Exception ignored)
		{
		}
		return lower.toUpperCase();
	}

	/**
	 * Scans for available language files in the jar and external config folder.
	 */
	public synchronized void scanAvailableLanguages()
	{
		Set<String> discovered = new LinkedHashSet<>();
		discovered.add(AUTO);

		// 1. Scan external config directory: config/DistantHorizons/lang/*.json
		try
		{
			File externalDir = getExternalLangDirectory();
			if (externalDir.exists() && externalDir.isDirectory())
			{
				File[] files = externalDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
				if (files != null)
				{
					for (File f : files)
					{
						String code = f.getName().substring(0, f.getName().length() - 5).toLowerCase();
						if (!code.isEmpty())
						{
							discovered.add(code);
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warn("Failed scanning external language folder: " + e.getMessage());
		}

		// 2. Scan JAR entries if running from JAR
		try
		{
			if (JarUtils.jarFile != null && JarUtils.jarFile.isFile() && JarUtils.jarFile.getName().endsWith(".jar"))
			{
				try (JarFile jar = new JarFile(JarUtils.jarFile))
				{
					Enumeration<JarEntry> entries = jar.entries();
					while (entries.hasMoreElements())
					{
						JarEntry entry = entries.nextElement();
						String name = entry.getName();
						if (name.startsWith(LANG_RESOURCE_PREFIX) && name.toLowerCase().endsWith(".json"))
						{
							String code = name.substring(LANG_RESOURCE_PREFIX.length(), name.length() - 5).toLowerCase();
							if (!code.isEmpty() && !code.contains("/"))
							{
								discovered.add(code);
							}
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.debug("Could not scan jar entries for languages: " + e.getMessage());
		}

		// 3. Scan directory in development / unpacked classpath
		try
		{
			URL url = JarUtils.class.getClassLoader().getResource(LANG_RESOURCE_PREFIX);
			if (url != null && "file".equalsIgnoreCase(url.getProtocol()))
			{
				File dir = new File(url.toURI());
				if (dir.exists() && dir.isDirectory())
				{
					File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
					if (files != null)
					{
						for (File f : files)
						{
							String code = f.getName().substring(0, f.getName().length() - 5).toLowerCase();
							if (!code.isEmpty())
							{
								discovered.add(code);
							}
						}
					}
				}
			}
		}
		catch (Exception ignored)
		{
		}

		// 4. Probe well-known fallback codes via getResourceAsStream
		for (String commonCode : COMMON_LANG_CODES)
		{
			if (!discovered.contains(commonCode))
			{
				InputStream in = JarUtils.accessFile(LANG_RESOURCE_PREFIX + commonCode + ".json");
				if (in != null)
				{
					try
					{
						in.close();
					}
					catch (Exception ignored)
					{
					}
					discovered.add(commonCode);
				}
			}
		}

		// Always ensure en_us and zh_cn are selectable options
		discovered.add(EN_US);

		List<LanguageOption> list = new ArrayList<>();
		list.add(new LanguageOption(AUTO, this.getDisplayNameForCode(AUTO)));

		List<String> sortedOthers = new ArrayList<>(discovered);
		sortedOthers.remove(AUTO);
		Collections.sort(sortedOthers);

		for (String code : sortedOthers)
		{
			list.add(new LanguageOption(code, this.getDisplayNameForCode(code)));
		}

		this.availableLanguages.clear();
		this.availableLanguages.addAll(list);
		this.scanned = true;
	}

	/**
	 * Sets the active language code (e.g. "auto", "zh_cn", "en_us").
	 */
	public synchronized void setLanguage(String langCode)
	{
		this.ensureFallbackLoaded();

		if (langCode == null || langCode.trim().isEmpty() || AUTO.equalsIgnoreCase(langCode.trim()))
		{
			this.currentLanguage = AUTO;
			this.activeTranslations.clear();
			LOGGER.info("Distant Horizons language set to Auto (following Minecraft).");
			return;
		}

		String normalized = langCode.trim().toLowerCase();
		this.currentLanguage = normalized;
		this.activeTranslations.clear();

		Map<String, String> loaded = this.loadLanguageMap(normalized);
		this.activeTranslations.putAll(loaded);
		LOGGER.info("Distant Horizons language loaded for [" + normalized + "] (" + loaded.size() + " entries).");
	}

	/**
	 * Translates a key using active language -> fallback (en_us).
	 * Returns null if no custom translation exists or language is set to AUTO.
	 */
	public String translate(String key)
	{
		if (this.isAuto())
		{
			return null;
		}
		String val = this.activeTranslations.get(key);
		if (val != null)
		{
			return val;
		}
		val = this.fallbackTranslations.get(key);
		if (val != null)
		{
			return val;
		}
		return null;
	}

	public boolean hasTranslation(String key)
	{
		if (this.isAuto())
		{
			return false;
		}
		return this.activeTranslations.containsKey(key) || this.fallbackTranslations.containsKey(key);
	}

	private void ensureFallbackLoaded()
	{
		if (this.fallbackTranslations.isEmpty())
		{
			Map<String, String> enUs = this.loadLanguageMap(EN_US);
			this.fallbackTranslations.putAll(enUs);
		}
	}

	private Map<String, String> loadLanguageMap(String langCode)
	{
		Map<String, String> result = new HashMap<>();

		// 1. Try classpath resource
		try (InputStream in = JarUtils.accessFile(LANG_RESOURCE_PREFIX + langCode + ".json"))
		{
			if (in != null)
			{
				String json = JarUtils.convertInputStreamToString(in);
				result.putAll(parseJsonToMap(json));
			}
		}
		catch (Exception e)
		{
			LOGGER.warn("Failed loading classpath language file for [" + langCode + "]: " + e.getMessage());
		}

		// 2. Try external file in config/DistantHorizons/lang/<langCode>.json (takes precedence)
		try
		{
			File extFile = new File(getExternalLangDirectory(), langCode + ".json");
			if (extFile.exists() && extFile.isFile())
			{
				String json = new String(Files.readAllBytes(extFile.toPath()), StandardCharsets.UTF_8);
				result.putAll(parseJsonToMap(json));
				LOGGER.info("Loaded external language overrides from: " + extFile.getAbsolutePath());
			}
		}
		catch (Exception e)
		{
			LOGGER.warn("Failed loading external language file for [" + langCode + "]: " + e.getMessage());
		}

		return result;
	}

	public static Map<String, String> parseJsonToMap(String jsonContent)
	{
		Map<String, String> map = new HashMap<>();
		if (jsonContent == null || jsonContent.trim().isEmpty())
		{
			return map;
		}
		try
		{
			Config jsonConfig = Config.inMemory();
			JsonFormat.minimalInstance().createParser().parse(jsonContent, jsonConfig, ParsingMode.REPLACE);
			extractMapEntries(jsonConfig.valueMap(), "", map);
		}
		catch (Exception e)
		{
			LOGGER.error("Failed to parse language JSON: " + e.getMessage(), e);
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private static void extractMapEntries(Map<String, Object> map, String prefix, Map<String, String> outMap)
	{
		for (Map.Entry<String, Object> entry : map.entrySet())
		{
			String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
			Object val = entry.getValue();
			if (val instanceof Map)
			{
				extractMapEntries((Map<String, Object>) val, key, outMap);
			}
			else if (val instanceof Config)
			{
				extractMapEntries(((Config) val).valueMap(), key, outMap);
			}
			else if (val != null)
			{
				outMap.put(key, val.toString());
			}
		}
	}

	public static File getExternalLangDirectory()
	{
		try
		{
			IMinecraftSharedWrapper mcShared = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
			if (mcShared != null && mcShared.getInstallationDirectory() != null)
			{
				File dir = new File(mcShared.getInstallationDirectory(), "config/" + ModInfo.NAME + "/lang");
				if (!dir.exists())
				{
					dir.mkdirs();
				}
				return dir;
			}
		}
		catch (Exception ignored)
		{
		}

		File fallback = new File("config/" + ModInfo.NAME + "/lang");
		if (!fallback.exists())
		{
			fallback.mkdirs();
		}
		return fallback;
	}
}
