/* 
 * Copyright (C) 2016-2022 by LA7ECA, Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package no.polaric.aprsdb;
import no.polaric.aprsd.*;
import java.util.*;
import java.io.*;
import uk.me.jstott.jcoord.*; 
import java.util.concurrent.*;
import java.util.Set;
import java.util.regex.*;
import java.util.stream.*;
import org.nustaq.serialization.FSTConfiguration;
import no.polaric.aprsd.filter.*;


/**
 * In-memory implementation of StationDB.
 * Data is saved to a file. Periodically and when program ends.
 */
public class DStationDBImp extends StationDBBase implements StationDB
{
    private PluginApi _dbp;
    private LRUCache<TrackerPoint> _cache;

    
    public DStationDBImp(ServerAPI api)
    {
        super(api);
        _cache = new LRUCache<TrackerPoint>(5000);

        /* Get plugin API */
        _dbp = (PluginApi) api.properties().get("aprsdb.plugin");

        restore();
    }
    
        
    
    
    
    /***********************************
     * Item methods (get/add, remove)
     ***********************************/
    
    /** 
     * Return the number of realtime items. 
     */   
    public int nItems() {     
        RtItemsDBSession db = null;
        try {
            db = new RtItemsDBSession(_dbp.getDB(true)); // Autocommit on
            return (int) db.getCount();
        }    
        catch(Exception e) {
            return 0;
        }     
        finally {
            if (db != null) db.close();
        }
    }
        
    
    /**
     * Convert from datbase data to TrackerPoint object. 
     */
    private TrackerPoint _bin2point(RtItemsDBSession.Item it) {
        /* FIXME: if object binary data is invalid or not saved, use 
         * the classname to create a new object.. */

        if (it == null || it.obj==null)
            return null;
        
        TrackerPoint p = _cache.get(it.ident);
        if (p==null) { 
            p = (TrackerPoint) fst.asObject(it.obj);
            if (p != null)
                _cache.put(it.ident, p);        
        }
        return p;
    }
    
    
    
    private TrackerPoint _bin2point2(RtItemsDBSession.RsItem it) {
        /* FIXME: if object binary data is invalid or not saved, use 
         * the classname to create a new object.. */

        if (it == null)
            return null;
        
        TrackerPoint p = _cache.get(it.ident);
        if (p==null) { 
            p = (TrackerPoint) fst.asObject(it.getObj());
            if (p != null)
                _cache.put(it.ident, p);
        }
        return p;
    }
    
    
    
    /**
     * Convert from TrackerPoint object to binary data (byte array).
     */
    private byte[] _point2bin(TrackerPoint pt) {
        _cache.remove(pt.getIdent());
        _cache.put(pt.getIdent(), pt);
        return fst.asByteArray(pt);
    }
    
    
    /**
     * Get item (TrackerPoint) from database. 
     */
    @Override protected TrackerPoint _getRtItem(String id) 
    {
        RtItemsDBSession db = null;
        try {
            db = new RtItemsDBSession(_dbp.getDB(true)); // Autocommit on
            RtItemsDBSession.Item item = db.getRtItem(id);
            return _bin2point(item);
        }    
        catch(Exception e) {
            _dbp.log().error("StationDBImp", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            return null;
        }     
        finally {
            if (db != null) db.close();
        }
    }
        
        
    /**
     * Add item (TrackerPoint) to database. 
     */
    @Override protected void _addRtItem(TrackerPoint s) {  
        RtItemsDBSession db = null;
        try {
            db = new RtItemsDBSession(_dbp.getDB());
            var item = new RtItemsDBSession.Item(
                s.getIdent(), s.getClass().getName(), 
                s.getPosition(), _point2bin(s), s.expired(), 
                s.getUpdated(), s.getDescr(), s.getTags().toArray(new String[0]) );
            db.removeRtItem(s.getIdent());
            db.addRtItem(item);
            db.commit();
        }    
        catch (java.sql.SQLException e) {
            _dbp.log().error("StationDBImp", "SQLException: "+e.getMessage());  
            db.abort();
        }
        catch (Exception e) {
            _dbp.log().error("StationDBImp", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            db.abort();
        }     
        finally {
            if (db != null) db.close();
        }
    }
    
    
    
    /**
     * Update an existing tracker point. 
     * @param s existing station
     */
    @Override public void updateItem(TrackerPoint s, LatLng prevpos) {
        RtItemsDBSession db = null;
        try {
            db = new RtItemsDBSession(_dbp.getDB());
            var item = new RtItemsDBSession.Item (
                s.getIdent(), s.getClass().getName(), 
                s.getPosition(), _point2bin(s), s.expired(),
                s.getUpdated(), s.getDescr(), s.getTags().toArray(new String[0]) );
            db.updateRtItem(item);
            db.commit();
        }    
        catch(Exception e) {
            _dbp.log().error("StationDBImp", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            db.abort();
        }     
        finally {
            if (db != null) db.close();
        }
    }

    
    
    @Override protected void _removeRtItem(String id) {
        RtItemsDBSession db = null;
        try {
            db = new RtItemsDBSession(_dbp.getDB());
            db.removeRtItem(id);
            db.commit();
        }    
        catch(Exception e) {
            _dbp.log().error("StationDBImp", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            db.abort();
        }     
        finally {
            if (db != null) db.close();
        }
        
    }    
        
        
        
        
    /****************************
     * Item search methods
     ****************************/
     
    /**
     * Return a list of trackerpoints where the ident has the given prefix. 
     * @Param srch Prefix 
     */
    public List<TrackerPoint> searchPrefix(String srch)
    {
        RtItemsDBSession db = null;
        try {
            db = new RtItemsDBSession(_dbp.getDB(true)); // Autocommit on
            List<TrackerPoint> res = new ArrayList();
            srch = srch.replaceAll("%", "");
            for (RtItemsDBSession.Item x : db.searchMatch(srch+"%", false, null)) 
                res.add( _bin2point(x) ); 
            return res;
        }    
        catch(Exception e) {
            _dbp.log().error("StationDBImp", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            return null;
        }     
        finally {
            if (db != null) db.close();
        }
    }
     
     
     
    /**
     * Search in the database of trackerpoints. 
     * Return a list of trackerpoints where ident or description matches the given search 
     * expression AND all the tags in the list.
     *
     * If the search expression is prefixed with "REG:", it is regarded as a regular 
     * expression, otherwise it is a simple wildcard expression.
     *
     * @param srch Search expression.
     * @param tags Array of tags (keywords). 
     */
    public List<TrackerPoint> search(String srch, String[] tags)
    {
        boolean regex = true;
        srch = srch.toUpperCase();
        if (srch.matches("REG:.*"))
            srch = srch.substring(4);
        else {
            regex = false;
            srch = srch.replaceAll("\\*", "%");
            srch = srch.replaceAll("\\?", "_");
        }
        
        RtItemsDBSession db = null;
        try {
            db = new RtItemsDBSession(_dbp.getDB(true)); // Autocommit on
            List<TrackerPoint> res = new ArrayList();

            for (RtItemsDBSession.Item it : db.searchMatch(srch, regex, tags)) {
                TrackerPoint p = _bin2point(it);             
                    res.add(p); 
            }
            return res;
        }    
        catch(Exception e) {
            _dbp.log().error("StationDBImp", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            return null;
        }     
        finally {
            if (db != null) db.close();
        }
    } 
     
     
     
    /**
     * Geographical search in the database of trackerpoints. 
     * Return list of stations within the rectangle defined by uleft (upper left 
     * corner) and lright (lower right corner).
     * @param uleft Upper left corner.
     * @param lright Lower right corner.
     */
    public List<TrackerPoint>
        search(Reference uleft, Reference lright, RuleSet filter)
    { 
        RtItemsDBSession db = null;
        try {
            long start = System.nanoTime();
            db = new RtItemsDBSession(_dbp.getDB()); // Autocommit on
            List<TrackerPoint> res = new ArrayList();
            String[] tags = filter.getTags(); 
            if (tags.length == 0)
                tags = null;
            db.searchGeo(uleft, lright, filter.getTags(), x -> {
               res.add( _bin2point2(x) ); 
            });
                
            long finish = System.nanoTime();
            long timeElapsed = finish - start;
            System.out.println("Search Trackerpoints - Time Elapsed (us): " + timeElapsed/1000);
            db.commit();
            return res;
        }    
        catch(Exception e) {
            db.abort();
            _dbp.log().error("StationDBImp", "Exception: "+e.getMessage());  
            e.printStackTrace(System.out);
            return null;
        }     
        finally {
            if (db != null) db.close();
        }
    }
    
    
    
    
    /****************************
     * Other methods
     ****************************/
    
    /**
     * Deactivate objects having the given owner and id.
     */
    public void deactivateSimilarObjects(String id, Station owner)
    {   
        /* Not necessary */
    }
    
    
    
    /**
     * Shutdown. May save state, etc.. 
     */
    public void shutdown() {
        save();
    }
    
    
    private void save() 
    {            
        _api.log().info("DStationDBImp", "Saving data...");
        RtItemsDBSession db = null;
        try {
            db = new RtItemsDBSession(_dbp.getDB());
            db.putSysObj("tags", PointObject.saveTags(fst));
            db.commit();
        }    
        catch(Exception e) {
            _dbp.log().error("DStationDBImp", "save - Exception: "+e.getMessage()); 
            db.abort();
        }     
        finally {
            if (db != null) db.close();
        }
    }
    
    
    private synchronized void restore()
    {
        _api.log().info("DStationDBImp", "Restoring data...");
        RtItemsDBSession db = null;
        try {
            db = new RtItemsDBSession(_dbp.getDB(true));
            PointObject.restoreTags(fst, db.getSysObj("tags") );
        }    
        catch(Exception e) {
            _dbp.log().error("DStationDBImp", "restore - Exception: "+e.getMessage()); 
        }     
        finally {
            if (db != null) db.close();
        }
    }
}
