package iped.viewers.timelinegraph.cache.persistance;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.jfree.data.time.TimePeriod;

import iped.app.ui.App;
import iped.viewers.timelinegraph.cache.CacheEventEntry;
import iped.viewers.timelinegraph.cache.CacheTimePeriodEntry;
import iped.viewers.timelinegraph.cache.TimeStampCache;

/*
 * Class implementing method for timeline chart cache persistance
 */

public class CachePersistance {
	File baseDir;
	
	HashMap<String,String> pathsToCheck = new HashMap<String,String>();
	
	static Thread t;

	public CachePersistance() {
		baseDir = new File(App.get().casesPathFile,"iped");
		baseDir = new File(baseDir,"data");
		baseDir = new File(baseDir,"timecache");
		try {
			//try to use case own folder
			if(!baseDir.mkdirs()) {
				//if not possible for security reasons, use user home folder
				getTempBaseDir();
			}
		}catch(SecurityException e) {
			//if not possible for security reasons, use user home folder
			getTempBaseDir();
		}
	}
	
	public void getTempBaseDir() {
		try {
			baseDir = new File(System.getProperty("user.home"),".iped");
			baseDir = new File(baseDir,"timecache");
			baseDir.mkdirs();
			
			boolean found=false;
			if(baseDir.listFiles()!=null) {
				for(File f:baseDir.listFiles()) {
					RandomAccessFile ras = new RandomAccessFile(new File(f,"case.txt"),"r");
					String casePath = ras.readUTF();
					ras.close();
					if(casePath.equals(App.get().casesPathFile.getAbsolutePath())) {
						baseDir = f;
						found=true;
						break;
					}else {
						pathsToCheck.put(casePath,f.getAbsolutePath());
					}
				}
			}
			if(!found) {
				String uuid = UUID.randomUUID().toString();
				baseDir = new File(baseDir,uuid);
				baseDir.mkdirs();
				RandomAccessFile ras = new RandomAccessFile(new File(baseDir,"case.txt"),"rw");
				ras.writeUTF(App.get().casesPathFile.getAbsolutePath().toString());
				ras.close();
			}

			//thread to clean caches with no correspondent original data
			if(t!=null) {
				//if it is null means it wasn't executed yet
				t = new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							for (Iterator iterator = pathsToCheck.keySet().iterator(); iterator.hasNext();) {
								String path = (String) iterator.next();
								File f = new File(path);
								if(!f.exists()) {
									f=new File(pathsToCheck.get(path));
									FileUtils.forceDelete(f);
								}
							}
						}catch(Exception e) {
							e.printStackTrace();
						}
					}
				});
				t.setPriority(Thread.MIN_PRIORITY);
				t.start();
			}
			
		}catch(Exception e2) {
			e2.printStackTrace();				
		}
	}

	public Map<String, List<CacheTimePeriodEntry>> loadNewCache(Class<? extends TimePeriod> className) throws IOException {
		Map<String, List<CacheTimePeriodEntry>> newCache = new HashMap<String, List<CacheTimePeriodEntry>>();
		
		for(File f : baseDir.listFiles()) {
			if(f.getName().equals(className.getSimpleName())) {
				ArrayList<CacheTimePeriodEntry> times = new ArrayList<CacheTimePeriodEntry>(); 
				newCache.put(f.getName(), times);
				if(!loadEventNewCache(times, f)) {
					throw new IOException("File not committed:"+f.getName());
				}
			}
		}
		
		return newCache;
	}

	private boolean loadEventNewCache(ArrayList<CacheTimePeriodEntry> times, File f) {
		try {
			RandomAccessFile dis;
			if(f.length()>0) {
				dis = new RandomAccessFile(new File(f,"0"),"r");
				
				int committed = dis.readShort(); 
				if (committed!=1) return false;
				
				String timezoneID = dis.readUTF();

				while(true) {
					Date d = new Date(dis.readLong());
					CacheTimePeriodEntry ct = new CacheTimePeriodEntry();
					ct.events = new ArrayList<CacheEventEntry>();
					ct.date = d;
					String eventName = dis.readUTF();
					while(!eventName.equals("!!")) {
						CacheEventEntry ce = new CacheEventEntry();
						ce.event = eventName;
						ce.docIds = new ArrayList<Integer>();
						int docId = dis.readInt();
						while(docId!=-1) {
							ce.docIds.add(docId);
							docId = dis.readInt();
						}
						ct.events.add(ce);
						eventName = dis.readUTF();
					}
					times.add(ct);
				}
			}
			return true;
		} catch (EOFException e) {
			return true;
		}catch (IOException e) {
			if(!(e instanceof EOFException)) {
				e.printStackTrace();
				return false;
			}
		}
		return true;
	}

	public void saveNewCache(TimeStampCache timeStampCache) {
		Map<String, List<CacheTimePeriodEntry>> newCache = timeStampCache.getNewCache();
		
		for (Entry<String, List<CacheTimePeriodEntry>> entry: newCache.entrySet()) {
			savePeriodNewCache(timeStampCache, entry.getValue(), new File(baseDir, entry.getKey()));
		}
	}

	private void savePeriodNewCache(TimeStampCache timeStampCache, List<CacheTimePeriodEntry> entry, File file) {
		file.mkdirs();
		File eventFile = new File(file, "0");
		
		Collections.sort(entry);
		
		try {
			eventFile.createNewFile();
			RandomAccessFile ras = new RandomAccessFile(eventFile, "rw");

			ras.writeShort(0);
			ras.writeUTF(timeStampCache.getCacheTimeZone().getID());
			
			for(int i=0; i<entry.size();i++) {
				CacheTimePeriodEntry ct = entry.get(i);
				ras.writeLong(ct.date.getTime());
				for(int j=0; j<ct.events.size(); j++) {
					CacheEventEntry ce = ct.events.get(j);
					ras.writeUTF(ce.event);
					for(int k=0; k<ce.docIds.size();k++) {
						ras.writeInt(ce.docIds.get(k));
					}
					ras.writeInt(-1);
				}
				ras.writeUTF("!!");
			}

			ras.seek(0);
			ras.writeShort(1);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}