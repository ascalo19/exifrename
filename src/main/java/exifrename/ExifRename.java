package exifrename;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rename one or more files using one of the EXIF dates found in it.
 *
 * @author Pascal Lovy <pascal.lovy@sysnet.ch>, 21-jun-2004
 */
public class ExifRename {

	private static final Log log = LogFactory.getLog(ExifRename.class);

	private static final Map<Pattern, DateFormat> regexDates = new HashMap<Pattern, DateFormat>();

	static {
		Locale en = new Locale("en_US");
		regexDates.put(Pattern.compile("(\\w{3} \\d{2} \\d{2}:\\d{2}:\\d{2} \\d{4})"), new SimpleDateFormat("MMM dd HH:mm:ss yyyy", en));
		regexDates.put(Pattern.compile("(\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2})"), new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", en));
		regexDates.put(Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[-|+]\\d{4})"), new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", en));
	}

	private List<String> pathList;
	private boolean dryRun = false;
	private boolean useIncrement = false;
	private boolean useLastModified = false;

	public static void main(String[] args) {
		try {
			List<String> argList = new ArrayList(Arrays.asList(args));
			log.debug("Command arguments " + argList);
			ExifRename er = new ExifRename();
			for (String arg : args) {
				if ("-d".equalsIgnoreCase(arg)) {
					argList.remove(arg);
					er.dryRun = true;
				}
				if ("-i".equalsIgnoreCase(arg)) {
					argList.remove(arg);
					er.useIncrement = true;
				}
				if ("-m".equalsIgnoreCase(arg)) {
					argList.remove(arg);
					er.useLastModified = true;
				}
			}
			er.pathList = argList;
			er.execute();
			System.exit(0);
		} catch (Exception e) {
			log.fatal("Unhandled exception", e);
			System.exit(1);
		}
	}

	public void execute() {
		log.debug("Processing path list " + pathList);
		for (String p : pathList) {
			File path = new File(p);
			log.debug("Current path " + p);
			if (path.isDirectory()) {
				log.debug("... is a directory");
				processDirectory(path);
			} else if (path.isFile()) {
				log.debug("... is a file");
				renameFile(path);
			} else {
				log.debug("... does not exist");
			}
		}
	}

	private void processDirectory(File directory) {
		File[] listFiles = directory.listFiles();
		log.debug("Processing directory " + directory);
		for (int i = 0; i < listFiles.length; i++) {
			log.debug("Current path " + listFiles[i]);
			if (listFiles[i].isFile()) {
				log.debug("... is a file");
				renameFile(listFiles[i]);
			} else {
				log.debug("... is not a file, ignoring");
			}
		}
	}

	private void renameFile(File file) {
		log.debug("Processing file " + file);
		try {
			Date exifDate = getExifDate(file);
			String fileExt = file.getName().substring(file.getName().lastIndexOf("."), file.getName().length());
			String newBaseName = new SimpleDateFormat("yyyyMMdd_HHmmss").format(exifDate);
			if (file.getName().startsWith(newBaseName)) {
				log.debug("File " + file.getPath() + " already renamed, skipping ...");
				return;
			}

			String newFileName = newBaseName + fileExt.toLowerCase();
			String newFilePath;
			if (file.getParent() != null) {
				newFilePath = file.getParent() + File.separator + newFileName;
			} else {
				newFilePath = newFileName;
			}

			File newFile = new File(newFilePath);
			if (newFile.exists()) {
				if (useIncrement) {
					for (int i = 2; i < 10; i++) {
						newFile = new File(newFilePath.substring(0, newFilePath.lastIndexOf(".")) + "_" + i + fileExt.toLowerCase());
						if (!newFile.exists()) {
							break;
						}
					}
				} else {
					log.debug("File " + newFile.getPath() + " already exists, skipping ...");
					return;
				}
			}

			log.debug("Renaming from " + file.getPath() + " to " + newFile.getPath());
			if (dryRun) {
				log.debug("*** DRY RUN / NOTHING CHANGED ***");
			} else {
				file.renameTo(newFile);
			}
		} catch (Exception e) {
			log.error("Cannot rename file " + file, e);
		}
	}

	private Date getExifDate(File file) throws Exception {
		log.debug("Looking for an EXIF date in file: " + file);
		// Hack to avoid ExifReader output on stdout: substitute it with a PipedOutputStream
		PrintStream out = System.out;
		System.setOut(new PrintStream(new PipedOutputStream()));

		Metadata metadata = null;
		try {
			metadata = ImageMetadataReader.readMetadata(file);
		} catch (Exception e) {
			log.debug("... no EXIF information found");
		}

		// Put back the real stdout
		System.out.close();
		System.setOut(out);

		Date dateTime = null;
		if (metadata != null) {
			Class[] cats = {
					ExifIFD0Directory.class,
					ExifSubIFDDirectory.class,
					ExifSubIFDDirectory.class,
					GpsDirectory.class};

			int[] tags = {
					ExifIFD0Directory.TAG_DATETIME,
					ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL,
					ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED,
					GpsDirectory.TAG_GPS_DATE_STAMP};

			for (int i = 0; i < cats.length; i++) {
				Directory directory = metadata.getDirectory(cats[i]);
				for (int j = 0; j < tags.length; j++) {
					try {
						dateTime = directory.getDate(tags[j]);
						if (dateTime != null) {
							log.debug("... found " + f(dateTime));
							return dateTime;
						}
					} catch (Exception e) {
						// Do nothing, try the next tag
						log.debug("... cannot get the date in metadata " + tags[j], e);
					}
				}
			}
		}

		byte[] buffer = new byte[2048];
		RandomAccessFile f = new RandomAccessFile(file, "r");
		try {
			f.read(buffer);
			dateTime = getRegexDate(new String(buffer));
			if (dateTime != null) {
				return dateTime;
			}
			Arrays.fill(buffer, (byte) 0);
			f.seek(f.length() - buffer.length);
			f.read(buffer);
			dateTime = getRegexDate(new String(buffer));
			if (dateTime != null) {
				return dateTime;
			}
		} finally {
			f.close();
		}


		if (useLastModified) {
			dateTime = new Date(file.lastModified());
			log.debug("... used file's lastModified " + f(dateTime));
			return dateTime;
		}

		log.debug("... no date found");
		throw new Exception("No date found in file " + file.getPath());
	}

	private Date getRegexDate(String data) throws ParseException {
		for (Pattern p : regexDates.keySet()) {
			Matcher m = p.matcher(data);
			if (m.find()) {
				String match = m.group(0);
				log.debug("... found " + match);
				DateFormat f = regexDates.get(p);
				return f.parse(match);
			}
		}
		return null;
	}

	private String f(Date dateTime) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(dateTime);
	}
}
