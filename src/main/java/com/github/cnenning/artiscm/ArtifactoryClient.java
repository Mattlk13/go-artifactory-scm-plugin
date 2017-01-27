package com.github.cnenning.artiscm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import com.thoughtworks.go.plugin.api.logging.Logger;

public class ArtifactoryClient {

	protected Logger logger = Logger.getLoggerFor(getClass());

	public void downloadFiles(String url, HttpClient client, String targetDirPath, String patternStr) throws ClientProtocolException, IOException {
		// create target dir
		File targetDir = new File(targetDirPath);
		if (!targetDir.exists()) {
			logger.info("creating target dir: " + targetDirPath);
			targetDir.mkdirs();
		}

		// add trailing slash
		if (!url.endsWith("/")) {
			url += "/";
		}

		Pattern pattern = null;
		if (patternStr != null && !patternStr.isEmpty()) {
			pattern = Pattern.compile(patternStr);
		}

		List<Revision> files = files(url, client);
		for (Revision rev : files) {
			String filename = rev.revision;
			if (pattern != null) {
				Matcher matcher = pattern.matcher(filename);
				if (!matcher.matches()) {
					continue;
				}
			}

			filename = escapeName(filename);
			String completeUrl = url + filename;

			logger.info("downloading " + completeUrl);

			HttpGet httpget = new HttpGet(completeUrl);
			HttpResponse response = client.execute(httpget);
			try {
				InputStream contentStream = response.getEntity().getContent();
				FileOutputStream outStream = new FileOutputStream(new File(targetDir, rev.revision));
				IOUtils.copy(contentStream, outStream);
			} finally {
				EntityUtils.consumeQuietly(response.getEntity());
			}
		}
	}

	protected String escapeName(String str) {
		try
		{
			return URLEncoder.encode(str, "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			e.printStackTrace();
			return str;
		}
	}

	public String checkSubDirs(final String url, final String pattern, final HttpClient client) throws ClientProtocolException, IOException {
		String dirName = downloadHtml(url, client, new Callback<String>() {
			@Override
			public String callback(String url, HttpClient client, Document document)
			{
				return containsSubDir(document, pattern);
			}
		});
		return dirName;
	}

	public String checkFiles(String url, String patternStr, HttpClient client) throws ClientProtocolException, IOException {
		Revision latest = latestChildMatching(url, patternStr, false, client);
		return latest != null ? latest.revision : null;
	}

	protected <T> T downloadHtml(String url, HttpClient client, Callback<T> callback) throws ClientProtocolException, IOException {
		// add trailing slash
		if (!url.endsWith("/")) {
			url += "/";
		}

		HttpGet httpget = new HttpGet(url);
		HttpResponse response = client.execute(httpget);
		try {
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode > 399) {
				throw new IOException("status code: " + statusCode);
			}
			String charsetName = charsetName(response);
			InputStream contentStream = response.getEntity().getContent();
			Document document = Jsoup.parse(contentStream, charsetName, url);
		
			return callback.callback(url, client, document);

		} finally {
			EntityUtils.consumeQuietly(response.getEntity());
		}
	}

	protected final static String CHARSET_KEY = "; charset=";

	protected String charsetName(HttpResponse response) {
		Header contentType = response.getFirstHeader("Content-Type");
		if (contentType != null) {
			String value = contentType.getValue();
			if (value != null) {
				int indexOfCharset = value.indexOf(CHARSET_KEY);
				if (indexOfCharset > 0) {
					return value.substring(indexOfCharset + CHARSET_KEY.length());
				}
			}
		}
		return null;
	}

	public static final String PARENT_DIR = "../";

	protected boolean isDir(String href) {
		return href != null && href.endsWith("/") && !href.equals(PARENT_DIR);
	}

	public static final Set<String> HASH_FILE_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"md5",
		"sha1")));

	protected boolean isFile(String href) {
		if (href != null && !href.endsWith("/")) {
			int lastIndexOfDot = href.lastIndexOf(".");
			if (lastIndexOfDot > 0) {
				String extension = href.substring(lastIndexOfDot + 1, href.length());
				return !HASH_FILE_EXTENSIONS.contains(extension);
			} else {
				return true;
			}
		}
		return false;
	}

	protected String containsSubDir(Document document, String patternStr) {
		Pattern pattern = null;
		if (patternStr != null && !patternStr.isEmpty()) {
			pattern = Pattern.compile(patternStr);
		}
		Elements links = document.select("a");
		for (Element link : links) {
			String href = link.attr("href");
			if (isDir(href) && dirMatchesPattern(href, pattern)) {
				return href;
			}
		}
		return null;
	}

	protected boolean dirMatchesPattern(String dir, Pattern pattern) {
		if (pattern != null) {
			if (dir.endsWith("/")) {
				dir = dir.substring(0, dir.length() - 1);
			}
			return pattern.matcher(dir).matches();
		}
		return true;
	}

	public Revision latestRevision(final String url, final HttpClient client) throws ClientProtocolException, IOException {
		return downloadHtml(url, client, new Callback<Revision>(){
			@Override
			public Revision callback(String url, HttpClient client, Document document) throws IOException
			{
				List<Revision> revisions = revisions(url, client, document, null);
				return !revisions.isEmpty() ? revisions.get(0) : null;
			}
		});
	}

	public List<Revision> latestRevisionsSince(final String url, final HttpClient client, final Date since) throws ClientProtocolException, IOException {
		return downloadHtml(url, client, new Callback<List<Revision>>(){
			@Override
			public List<Revision> callback(String url, HttpClient client, Document document) throws IOException
			{
				return revisions(url, client, document, since);
			}
		});
	}

	protected List<Revision> revisions(String url, HttpClient client, Document document, Date since) throws ClientProtocolException, IOException {
		List<Revision> revisions = new ArrayList<>();
		Elements links = document.select("a");
		for (Element link : links) {
			String href = link.attr("href");
			if (isDir(href)) {
				Revision rev = elementToRev(link, since, url);
				if (rev != null) {
					revisions.add(rev);
					if (since != null) {
						filesForRev(url, client, rev);
					}
				}
			}
		}
		if (since == null && !revisions.isEmpty()) {
			// assume revisions are ordered by date
			Revision lastRev = revisions.get(revisions.size() - 1);
			filesForRev(url, client, lastRev);
			revisions = Arrays.asList(lastRev);
		}
		return revisions;
	}

	protected void filesForRev(String url, HttpClient client, Revision rev) throws ClientProtocolException, IOException
	{
		String revUrl = url + rev.revision;
		List<Revision> fileRevs = files(revUrl, client);
		List<String> files = new ArrayList<>(fileRevs.size());
		for (Revision fileRev : fileRevs) {
			files.add(fileRev.revision);
		}
		rev.files = files;
	}

	protected List<Revision> files(String url, HttpClient client) throws ClientProtocolException, IOException {
		return children(url, false, client);
	}

	protected List<Revision> children(final String url, final boolean directories, final HttpClient client) throws ClientProtocolException, IOException {
		return downloadHtml(url, client, new Callback<List<Revision>>() {
			@Override
			public List<Revision> callback(String url, HttpClient client, Document document)
			{
				List<Revision> revisions = new ArrayList<>();
				Elements links = document.select("a");
				for (Element link : links) {
					String href = link.attr("href");
					boolean isCorrectType = directories
						? isDir(href)
						: isFile(href);
					if (isCorrectType) {
						Revision rev = elementToRev(link, null, url);
						if (rev != null) {
							revisions.add(rev);
						}
					}
				}
				return revisions;
			}
		});
	}

	public Revision latestChildMatching(String url, String patternStr, boolean directory, HttpClient client) throws ClientProtocolException, IOException {
		if (patternStr == null || patternStr.isEmpty()) {
			return null;
		}

		Revision latest = null;
		Pattern pattern = Pattern.compile(patternStr);
		List<Revision> children = children(url, directory, client);
		for (Revision rev : children) {
			String name = rev.revision;
			Matcher matcher = pattern.matcher(name);
			if (!matcher.matches()) {
				continue;
			}
			if (latest == null || latest.timestamp.compareTo(rev.timestamp) < 0) {
				latest = rev;
				int groupCount = matcher.groupCount();
				List<String> groups = new ArrayList<>(groupCount);
				for (int i=0; i<groupCount; i++) {
					String group = matcher.group(i);
					groups.add(group);
				}
				latest.matchingGroups = groups;
			}
		}
		return latest;
	}

	protected Revision elementToRev(Element link, Date since, String url) {
		String name = link.text();
		Node nextSibling = link.nextSibling();
		if (nextSibling instanceof TextNode) {
			TextNode textNode = (TextNode) nextSibling;
			String text = textNode.text();
			Date date = findDateInText(text, url);

			if (since == null || date.getTime() > since.getTime()) {
				// remove trailing slash
				if (name.endsWith("/")) {
					name = name.substring(0, name.length() - 1);
				}

				Revision rev = new Revision();
				rev.revision = name;
				rev.comment = name;
				rev.timestamp = date;
				return rev;
			}
		}
		return null;
	}

	public static final String HTML_DATE_FORMAT_STR = "dd-MMM-yyyy HH:mm";
	public static final DateTimeFormatter HTML_DATE_FORMATTER = DateTimeFormat.forPattern(HTML_DATE_FORMAT_STR);

	protected Date findDateInText(String text, String url) {
		if (text != null) {
			text = text.trim();
			try {
				text = text.substring(0, HTML_DATE_FORMAT_STR.length());
				return HTML_DATE_FORMATTER.parseDateTime(text).toDate();
			} catch (Exception e) {
				logger.warn("could not parse date: '" + text + "', url: " + url);
				logger.debug(e.getMessage(), e);
			}
		}
		return new Date(0);
	}

	public static class Revision {
		String revision;
		Date timestamp;
		String comment;
		List<String> files;
		List<String> matchingGroups;
	}

	protected static interface Callback<T> {
		T callback(String url, HttpClient client, Document document) throws IOException;
	}
}
