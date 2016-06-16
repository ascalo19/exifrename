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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Rename one or more files using one of the EXIF dates found in it.
 *
 * @author Pascal Lovy <pascal.lovy@sysnet.ch>, 21-jun-2004
 */
public class ExifRename {

	private static final Log log = LogFactory.getLog(ExifRename.class);

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
			String newFileName = new SimpleDateFormat("yyyyMMdd_HHmmss").format(exifDate) + fileExt.toLowerCase();
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
					FileTime timestamp = Files.getLastModifiedTime(Paths.get(file.getAbsolutePath()));
					String s = String.valueOf(timestamp.toMillis());
					log.debug(" using millis " + s);
					newFile = new File(newFilePath.substring(0, newFilePath.lastIndexOf(".")) + "_" + s.substring(s.length() - 3, s.length()) + fileExt.toLowerCase());
					if (newFile.exists()) {
						log.debug("File " + newFile.getPath() + " already exists, skipping ...");
						return;
					}
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
			throw new Exception("No EXIF information found in file " + file.getPath(), e);
		}

		// Put back the real stdout
		System.out.close();
		System.setOut(out);

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

		Date dateTime = null;
		if (metadata != null) {
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

		if (useLastModified) {
			dateTime = new Date(file.lastModified());
			log.debug("... used file's lastModified " + f(dateTime));
			return dateTime;
		}

		log.debug("... no date found");
		throw new Exception("No date found in file " + file.getPath());
	}

	private String f(Date dateTime) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(dateTime);
	}
}
