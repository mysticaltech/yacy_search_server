// yacySeedDB.java 
// -------------------------------------
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004, 2005
// last major change: 22.02.2005
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

package de.anomic.yacy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;

import de.anomic.http.httpc;
import de.anomic.kelondro.kelondroDyn;
import de.anomic.kelondro.kelondroException;
import de.anomic.kelondro.kelondroMScoreCluster;
import de.anomic.kelondro.kelondroMap;
import de.anomic.kelondro.kelondroRecords;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverCore;
import de.anomic.server.serverSwitch;
import de.anomic.server.logging.serverLog;
import de.anomic.tools.disorderHeap;

public final class yacySeedDB {
    
    // global statics
    public static final int commonHashLength = 12;
    // this is the lenght of the hash key that is used:
    // - for seed hashes (this Object)
    // - for word hashes (plasmaIndexEntry.wordHashLength)
    // - for L-URL hashes (plasmaLURL.urlHashLength)
    // these hashes all shall be generated by base64.enhancedCoder

    public static final String[] sortFields = new String[] {"LCount", "ICount", "Uptime", "Version", yacySeed.STR_LASTSEEN};
    public static final String[]  accFields = new String[] {"LCount", "ICount", "ISpeed"};
    
    // class objects
    private File seedActiveDBFile, seedPassiveDBFile, seedPotentialDBFile;

    private disorderHeap seedQueue;
    private kelondroMap seedActiveDB, seedPassiveDB, seedPotentialDB;
    private int seedDBBufferKB;
    
    public  final plasmaSwitchboard sb;
    public  yacySeed mySeed; // my own seed
    public  final File myOwnSeedFile;
    private final Hashtable nameLookupCache;
    
    
    public yacySeedDB(plasmaSwitchboard sb,
            File seedActiveDBFile,
            File seedPassiveDBFile,
            File seedPotentialDBFile,
            int bufferkb) throws IOException {
        
        this.seedDBBufferKB = bufferkb;
        this.seedActiveDBFile = seedActiveDBFile;
        this.seedPassiveDBFile = seedPassiveDBFile;
        this.seedPotentialDBFile = seedPotentialDBFile;
        this.mySeed = null; // my own seed
        this.sb = sb;
        
        // set up seed database
        seedActiveDB = openSeedTable(seedActiveDBFile);
        seedPassiveDB = openSeedTable(seedPassiveDBFile);
        seedPotentialDB = openSeedTable(seedPotentialDBFile);
        
        // create or init own seed
        myOwnSeedFile = new File(sb.getRootPath(), sb.getConfig("yacyOwnSeedFile", "mySeed.txt"));
        if (myOwnSeedFile.exists() && (myOwnSeedFile.length() > 0)) {
            // load existing identity
            mySeed = yacySeed.load(myOwnSeedFile);
        } else {
            // create new identity
            mySeed = yacySeed.genLocalSeed(sb);
            // save of for later use
            mySeed.save(myOwnSeedFile); // in a file
            //writeMap(mySeed.hash, mySeed.dna, "new"); // in a database
        }
        
        if (sb.getConfig("portForwardingEnabled","false").equalsIgnoreCase("true")) {
            mySeed.put("Port", sb.getConfig("portForwardingPort","8080"));
            mySeed.put("IP", sb.getConfig("portForwardingHost","localhost"));
        } else {
            mySeed.put("IP", "");       // we delete the old information to see what we have now
            mySeed.put("Port", sb.getConfig("port", "8080")); // set my seed's correct port number
        }
        mySeed.put("PeerType", "virgin"); // markup startup condition
        
        // start our virtual DNS service for yacy peers with empty cache
        nameLookupCache = new Hashtable();
        
        // check if we are in the seedCaches: this can happen if someone else published our seed
        removeMySeed();
        
        // set up seed queue (for probing candidates)
        seedQueue = null;
    }
    
    public synchronized void removeMySeed() {
        try {
            seedActiveDB.remove(mySeed.hash);
            seedPassiveDB.remove(mySeed.hash);
            seedPotentialDB.remove(mySeed.hash);
        } catch (IOException e) {}
    }
    
    public int[] dbCacheChunkSize() {
        int[] ac = seedActiveDB.cacheChunkSize();
        int[] pa = seedPassiveDB.cacheChunkSize();
        int[] po = seedPotentialDB.cacheChunkSize();
        int[] i = new int[3];
        i[kelondroRecords.CP_LOW] = (ac[kelondroRecords.CP_LOW] + pa[kelondroRecords.CP_LOW] + po[kelondroRecords.CP_LOW]) / 3;
        i[kelondroRecords.CP_MEDIUM] = (ac[kelondroRecords.CP_MEDIUM] + pa[kelondroRecords.CP_MEDIUM] + po[kelondroRecords.CP_MEDIUM]) / 3;
        i[kelondroRecords.CP_HIGH] = (ac[kelondroRecords.CP_HIGH] + pa[kelondroRecords.CP_HIGH] + po[kelondroRecords.CP_HIGH]) / 3;
        return i;
    }
    
    public int[] dbCacheFillStatus() {
        int[] ac = seedActiveDB.cacheFillStatus();
        int[] pa = seedPassiveDB.cacheFillStatus();
        int[] po = seedPotentialDB.cacheFillStatus();
        return new int[]{ac[0] + pa[0] + po[0], ac[1] + pa[1] + po[1], ac[2] + pa[2] + po[2], ac[3] + pa[3] + po[3]};
    }
    
    private synchronized kelondroMap openSeedTable(File seedDBFile) throws IOException {
        if (seedDBFile.exists()) try {
            // open existing seed database
            return new kelondroMap(new kelondroDyn(seedDBFile, (seedDBBufferKB * 0x400) / 3), sortFields, accFields);
        } catch (kelondroException e) {
            // if we have an error, we start with a fresh database
            if (seedDBFile.exists()) seedDBFile.delete();
        } catch (IOException e) {
            // if we have an error, we start with a fresh database
            if (seedDBFile.exists()) seedDBFile.delete();
        }
        // create new seed database
        new File(seedDBFile.getParent()).mkdir();
        return new kelondroMap(new kelondroDyn(seedDBFile, (seedDBBufferKB * 0x400) / 3, commonHashLength, 480), sortFields, accFields);
    }
    
    private synchronized kelondroMap resetSeedTable(kelondroMap seedDB, File seedDBFile) {
        // this is an emergency function that should only be used if any problem with the
        // seed.db is detected
	yacyCore.log.logFine("seed-db " + seedDBFile.toString() + " reset (on-the-fly)");
        try {
            seedDB.close();
            seedDBFile.delete();
            // create new seed database
            seedDB = openSeedTable(seedDBFile);
        } catch (IOException e) {
            yacyCore.log.logFine("resetSeedTable", e);
        }
        return seedDB;
    }
    
    public synchronized void resetActiveTable() { seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile); }
    public synchronized void resetPassiveTable() { seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile); }
    public synchronized void resetPotentialTable() { seedPotentialDB = resetSeedTable(seedPotentialDB, seedPotentialDBFile); }
    
    public void close() {
        try {
            seedActiveDB.close();
            seedPassiveDB.close();
        } catch (IOException e) {
            yacyCore.log.logFine("close", e);
        }
    }

    public Enumeration seedsSortedConnected(boolean up, String field) {
        // enumerates seed-type objects: all seeds sequentially ordered by field
	return new seedEnum(up, field, seedActiveDB);
    }
    
    public Enumeration seedsSortedDisconnected(boolean up, String field) {
        // enumerates seed-type objects: all seeds sequentially ordered by field
	return new seedEnum(up, field, seedPassiveDB);
    }
    
    public Enumeration seedsSortedPotential(boolean up, String field) {
        // enumerates seed-type objects: all seeds sequentially ordered by field
	return new seedEnum(up, field, seedPotentialDB);
    }
        
    public Enumeration seedsConnected(boolean up, boolean rot, String firstHash) {
        // enumerates seed-type objects: all seeds sequentially without order
	return new seedEnum(up, rot, (firstHash == null) ? null : firstHash.getBytes(), seedActiveDB);
    }
    
    public Enumeration seedsDisconnected(boolean up, boolean rot, String firstHash) {
        // enumerates seed-type objects: all seeds sequentially without order
	return new seedEnum(up, rot, (firstHash == null) ? null : firstHash.getBytes(), seedPassiveDB);
    }
        
    public Enumeration seedsPotential(boolean up, boolean rot, String firstHash) {
        // enumerates seed-type objects: all seeds sequentially without order
	return new seedEnum(up, rot, (firstHash == null) ? null : firstHash.getBytes(), seedPotentialDB);
    }
    
    public yacySeed anySeed() {
	// return just any probe candidate
	yacySeed seed;
	if ((seedQueue == null) || (seedQueue.size() == 0)) {
	    if (seedActiveDB.size() <= 0) return null;

	    // fill up the queue
	    seedQueue = new disorderHeap();
            Iterator keyIt;
            try {
                keyIt = seedActiveDB.keys(true, false); // iteration of String - Objects
            } catch (IOException e) {
                yacyCore.log.logSevere("yacySeedCache.anySeed: seed.db not available: " + e.getMessage());
                keyIt = (new HashSet()).iterator();
            }
	    String seedHash;
	    String myIP = (mySeed == null) ? "" : ((String) mySeed.get("IP", "127.0.0.1"));
	    while (keyIt.hasNext()) {
		seedHash = (String) keyIt.next();
		try {
		    seed = new yacySeed(seedHash, seedActiveDB.get(seedHash));
		    // check here if the seed is equal to the own seed
		    // this should never be the case, but it happens if a redistribution circle exists
		    if ((mySeed != null) && (seedHash.equals(mySeed.hash))) {
			// this seed should not be in the database
			seedActiveDB.remove(seedHash);
		    } else {
			// add to queue
			seedQueue.add(seed);
		    }
		} catch (IOException e) {}
	    }
	    // the queue is filled up!
	}
	if ((seedQueue == null) || (seedQueue.size() == 0)) return null;
	return (yacySeed) seedQueue.remove();
    }

    public yacySeed anySeedType(String type) {
	// this returns any seed that has a special PeerType
	yacySeed ys;
	String t;
	for (int i = 0; i < seedActiveDB.size(); i++) {
	    ys = anySeed();
	    if (ys == null) return null;
	    t = (String) ys.get("PeerType", "");
	    if ((t != null) && (t.equals(type))) return ys;
	}
	return null;
    }

    public yacySeed[] seedsByAge(boolean up, int count) {
        if (count > sizeConnected()) count = sizeConnected();

        // fill a score object
        kelondroMScoreCluster seedScore = new kelondroMScoreCluster();
        yacySeed ys;
        long absage;
        Enumeration s = seedsConnected(true, false, null);
        int searchcount = 1000;
        if (searchcount > sizeConnected()) searchcount = sizeConnected();
        try {
            while ((s.hasMoreElements()) && (searchcount-- > 0)) {
                ys = (yacySeed) s.nextElement();
                if ((ys != null) && (ys.get("LastSeen", "").length() > 10)) try {
                    absage = Math.abs(System.currentTimeMillis() - ys.getLastSeenTime());
                    seedScore.addScore(ys.hash, (int) absage);
                } catch (Exception e) {}
            }
            
            // result is now in the score object; create a result vector
            yacySeed[] result = new yacySeed[count];
            Iterator it = seedScore.scores(up);
            int c = 0;
            while ((c < count) && (it.hasNext())) result[c++] = getConnected((String) it.next());
            return result;
        } catch (kelondroException e) {
            seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);
            yacyCore.log.logFine("Internal Error at yacySeedDB.seedsByAge: " + e.getMessage(), e);
            return null;
        }
    }

    public int sizeConnected() {
	return seedActiveDB.size();
        /*
        Enumeration e = seedsConnected(true, false, null);
        int c = 0; while (e.hasMoreElements()) {c++; e.nextElement();}
        return c;
        */
    }
    
    public int sizeDisconnected() {
	return seedPassiveDB.size();
        /*
        Enumeration e = seedsDisconnected(true, false, null);
        int c = 0; while (e.hasMoreElements()) {c++; e.nextElement();}
        return c;
        */
    }
    
    public int sizePotential() {
	return seedPotentialDB.size();
        /*
        Enumeration e = seedsPotential(true, false, null);
        int c = 0; while (e.hasMoreElements()) {c++; e.nextElement();}
        return c;
        */
    }
    
    public long countActiveURL() { return seedActiveDB.getAcc("LCount"); }
    public long countActiveRWI() { return seedActiveDB.getAcc("ICount"); }
    public long countActivePPM() { return seedActiveDB.getAcc("ISpeed"); }
    public long countPassiveURL() { return seedPassiveDB.getAcc("LCount"); }
    public long countPassiveRWI() { return seedPassiveDB.getAcc("ICount"); }
    public long countPotentialURL() { return seedPotentialDB.getAcc("LCount"); }
    public long countPotentialRWI() { return seedPotentialDB.getAcc("ICount"); }

    public synchronized void addConnected(yacySeed seed) {
        if ((seed == null) || (seed.isProper() != null)) return;
        //seed.put("LastSeen", yacyCore.shortFormatter.format(new Date(yacyCore.universalTime())));
        try {
            nameLookupCache.put(seed.getName(), seed);
            seedActiveDB.set(seed.hash, seed.getMap());
            seedPassiveDB.remove(seed.hash);
            seedPotentialDB.remove(seed.hash);
        } catch (IOException e){
            yacyCore.log.logFine("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);            
        } catch (kelondroException e){
            yacyCore.log.logFine("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);            
        } catch (IllegalArgumentException e) {
            yacyCore.log.logFine("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);
        }
    }
    
    public synchronized void addDisconnected(yacySeed seed) {
	if (seed == null) return;
        try {
            nameLookupCache.remove(seed.getName());
            seedActiveDB.remove(seed.hash);
            seedPotentialDB.remove(seed.hash);
        } catch (Exception e) {}
	//seed.put("LastSeen", yacyCore.shortFormatter.format(new Date(yacyCore.universalTime())));
        try {
            seedPassiveDB.set(seed.hash, seed.getMap());
	} catch (IOException e) {
	    yacyCore.log.logFine("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
	    seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
	} catch (kelondroException e) {
	    yacyCore.log.logFine("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
	    seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
        } catch (IllegalArgumentException e) {
	    yacyCore.log.logFine("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
	    seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
	}
    }
    
    public synchronized void addPotential(yacySeed seed) {
        if (seed == null) return;
        try {
            nameLookupCache.remove(seed.getName());
            seedActiveDB.remove(seed.hash);
            seedPassiveDB.remove(seed.hash);
        } catch (Exception e) {}
	if (seed.isProper() != null) return;
	//seed.put("LastSeen", yacyCore.shortFormatter.format(new Date(yacyCore.universalTime())));
        try {
            seedPotentialDB.set(seed.hash, seed.getMap());
	} catch (IOException e) {
	    yacyCore.log.logFine("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
	    seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
	} catch (kelondroException e) {
	    yacyCore.log.logFine("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
	    seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
	} catch (IllegalArgumentException e) {
	    yacyCore.log.logFine("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
	    seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
	}
    }
        
    public boolean hasConnected(String hash) {
	try {
	    return (seedActiveDB.get(hash) != null);
	} catch (IOException e) {
	    return false;
	}
    }

    public boolean hasDisconnected(String hash) {
	try {
	    return (seedPassiveDB.get(hash) != null);
	} catch (IOException e) {
	    return false;
	}
    }
 
    public boolean hasPotential(String hash) {
	try {
	    return (seedPotentialDB.get(hash) != null);
	} catch (IOException e) {
	    return false;
	}
    }
        
    private yacySeed get(String hash, kelondroMap database) {
        if (hash == null) return null;
        if ((mySeed != null) && (hash.equals(mySeed.hash))) return mySeed;
	try {
	    Map entry = database.get(hash);
	    if (entry == null) return null;
	    return new yacySeed(hash, entry);
	} catch (IOException e) {
	    return null;
	}
    }
    
    public yacySeed getConnected(String hash) {
        return get(hash, seedActiveDB);
    }

    public yacySeed getDisconnected(String hash) {
        return get(hash, seedPassiveDB);
    }
        
    public yacySeed getPotential(String hash) {
        return get(hash, seedPotentialDB);
    }
    
    public yacySeed get(String hash) {
        yacySeed seed = getConnected(hash);
        if (seed == null) seed = getDisconnected(hash);
        if (seed == null) seed = getPotential(hash);
        return seed;
    }
    
    public yacySeed lookupByName(String peerName) {
        // reads a seed by searching by name
        
        // local peer?
        if (peerName.equals("localpeer")) return mySeed;
        
        // then try to use the cache
        yacySeed seed = (yacySeed) nameLookupCache.get(peerName);
        if (seed != null) return seed;

        // enumerate the cache and simultanous insert values
        Enumeration e = seedsConnected(true, false, null);
        String name;
        while (e.hasMoreElements()) {
            seed = (yacySeed) e.nextElement();
	    if (seed != null) {
		name = seed.getName().toLowerCase();
		if (seed.isProper() == null) nameLookupCache.put(name, seed);
		if (name.equals(peerName)) return seed;
	    }
        }
        // check local seed
        name = mySeed.getName().toLowerCase();
        if (mySeed.isProper() == null) nameLookupCache.put(name, mySeed);
        if (name.equals(peerName)) return mySeed;
        // nothing found
        return null;
    }
    
    public ArrayList storeCache(File seedFile) throws IOException {
	return storeCache(seedFile, false);
    }

    private ArrayList storeCache(File seedFile, boolean addMySeed) throws IOException {
        PrintWriter pw = null;
        ArrayList v = new ArrayList(seedActiveDB.size()+1);
        try {
            
            pw = new PrintWriter(new BufferedWriter(new FileWriter(seedFile)));
            
            // store own seed
            String line;
            if ((addMySeed) && (mySeed != null)) {
                line = mySeed.genSeedStr(null);
                v.add(line);
                pw.print(line + serverCore.crlfString);
            }
            
            // store other seeds
            yacySeed ys;
            for (int i = 0; i < seedActiveDB.size(); i++) {
                ys = anySeed();
                if (ys != null) {
                    line = ys.genSeedStr(null);
                    v.add(line);
                    pw.print(line + serverCore.crlfString);
                }
            }
            pw.flush();
        } finally {
            if (pw != null) try { pw.close(); } catch (Exception e) {}
        }
        return v;
    }

    public String uploadCache(yacySeedUploader uploader, 
            serverSwitch sb,
            yacySeedDB seedDB,
//          String  seedFTPServer,
//          String  seedFTPAccount,
//          String  seedFTPPassword,
//          File    seedFTPPath,
            URL     seedURL) throws Exception {
        
        // upload a seed file, if possible
        if (seedURL == null) throw new NullPointerException("UPLOAD - Error: URL not given");
        
        String log = null; 
        File seedFile = null;
        try {            
            // create a seed file which for uploading ...            
            seedFile = new File("seedFile.txt");
            serverLog.logFine("YACY","SaveSeedList: Storing seedlist into tempfile " + seedFile.toString());
            ArrayList uv = storeCache(seedFile, true);            
            
            // uploading the seed file
            serverLog.logFine("YACY","SaveSeedList: Trying to upload seed-file, " + seedFile.length() + " bytes, " + uv.size() + " entries.");
            log = uploader.uploadSeedFile(sb,seedDB,seedFile);
            
            // check also if the result can be retrieved again
            serverLog.logFine("YACY","SaveSeedList: Checking uploading success ...");
            if (checkCache(uv, seedURL))
                log = log + "UPLOAD CHECK - Success: the result vectors are equal" + serverCore.crlfString;
            else {
                throw new Exception("UPLOAD CHECK - Error: the result vector is different" + serverCore.crlfString);
            }
        } finally {
            if (seedFile != null) seedFile.delete();
        }
        
        return log;
    }
        
    public String copyCache(File seedFile, URL seedURL) throws IOException {
        if (seedURL == null) return "COPY - Error: URL not given";
        ArrayList uv = storeCache(seedFile, true);
        try {
            // check also if the result can be retrieved again
            if (checkCache(uv, seedURL))
                return "COPY CHECK - Success: the result vectors are equal" + serverCore.crlfString;
            else
                return "COPY CHECK - Error: the result vector is different" + serverCore.crlfString;
        } catch (IOException e) {
            return "COPY CHECK - Error: IO problem " + e.getMessage() + serverCore.crlfString;
        }
    }

    private boolean checkCache(ArrayList uv, URL seedURL) throws IOException {        
        // check if the result can be retrieved again
        ArrayList check  = httpc.wget(seedURL, 10000, null, null, sb.remoteProxyHost, sb.remoteProxyPort);
        
        if (check == null) {
            serverLog.logFine("YACY","SaveSeedList: Testing download failed ...");
        }
                
        if ((check == null) || (uv == null) || (uv.size() != check.size())) {
            serverLog.logFine("YACY","SaveSeedList: Local and uploades seed-list " +
                               "contains varying numbers of entries." +
                               "\n\tLocal seed-list:  " + uv.size() + " entries" + 
                               "\n\tRemote seed-list: " + check.size() + " enties");
            return false;
        } else {
            serverLog.logFine("YACY","SaveSeedList: Comparing local and uploades seed-list entries ...");
            int i;
            for (i = 0; i < uv.size(); i++) {
                if (!(((String) uv.get(i)).equals((String) check.get(i)))) return false;
            }
            if (i == uv.size()) return true;
        }
        return false;
    }

    public String resolveYacyAddress(String host) {
        yacySeed seed;
        int p;
        String subdom = null;
        if (host.endsWith(".yacyh")) {
            // this is not functional at the moment
            // caused by lowecasing of hashes at the browser client
            p = host.indexOf(".");
            if ((p > 0) && (p != (host.length() - 6))) {
                subdom = host.substring(0, p);
                host = host.substring(p + 1);
            }
            // check remote seeds
            seed = getConnected(host.substring(0, host.length() - 6)); // checks only remote, not local
            // check local seed
            if (seed == null) {
                if (host.substring(0, host.length() - 6).equals(mySeed.hash))
                    seed = mySeed;
                else return null;
            }
            return seed.getAddress() + ((subdom == null) ? "" : ("/" + subdom));
        } else if (host.endsWith(".yacy")) {
            // identify subdomain
            p = host.indexOf(".");
            if ((p > 0) && (p != (host.length() - 5))) {
                subdom = host.substring(0, p); // no double-dot attack possible, the subdom cannot have ".." in it
                host = host.substring(p + 1); // if ever, the double-dots are here but do not harm
            }
            // identify domain
            String domain = host.substring(0, host.length() - 5).toLowerCase();
            seed = lookupByName(domain);
            if (seed == null) return null;
            if ((seed == mySeed) && (!(seed.isOnline()))) {
                // take local ip instead of external
                return serverCore.publicIP() + ":" + sb.getConfig("port", "8080") + ((subdom == null) ? "" : ("/" + subdom));
            }
            return seed.getAddress() + ((subdom == null) ? "" : ("/" + subdom));
        } else {
            return null;
        }
    }

    class seedEnum implements Enumeration {

	kelondroMap.mapIterator it;
	yacySeed nextSeed;
        kelondroMap database;

	public seedEnum(boolean up, boolean rot, byte[] firstKey, kelondroMap database) {
            this.database = database;
	    try {
		it = (firstKey == null) ? database.maps(up, rot) : database.maps(up, rot, firstKey);
		nextSeed = internalNext();
	    } catch (IOException e) {
		yacyCore.log.logFine("ERROR seedLinEnum: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == seedActiveDB) seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);
                if (database == seedPassiveDB) seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
		it = null;
	    } catch (kelondroException e) {
		yacyCore.log.logFine("ERROR seedLinEnum: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == seedActiveDB) seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);
                if (database == seedPassiveDB) seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
		it = null;
	    }
	}

        public seedEnum(boolean up, String field, kelondroMap database) {
            this.database = database;
	    try {
		it = database.maps(up, field);
		nextSeed = internalNext();
	    } catch (kelondroException e) {
		yacyCore.log.logFine("ERROR seedLinEnum: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == seedActiveDB) seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);
                if (database == seedPassiveDB) seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
                if (database == seedPotentialDB) seedPotentialDB = resetSeedTable(seedPotentialDB, seedPotentialDBFile);
		it = null;
	    }
        }
                
	public boolean hasMoreElements() {
	    return (nextSeed != null);
	}

	public yacySeed internalNext() {
	    if ((it == null) || (!(it.hasNext()))) return null;
            Map dna = (Map) it.next();
            String hash = (String) dna.remove("key");
            return new yacySeed(hash, dna);
	}

	public Object nextElement() {
	    yacySeed seed = nextSeed;
	    nextSeed = internalNext();
	    return seed;
	}

    }

}
