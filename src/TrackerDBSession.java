 
/* 
 * Copyright (C) 2014-2023 by Øyvind Hanssen (ohanssen@acm.org)
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
 *
 */

package no.polaric.aprsdb;
import  no.polaric.aprsd.*;
import  java.text.*;
import  java.sql.*;
import  javax.sql.*;
import  java.util.concurrent.locks.*; 
import  no.polaric.aprsd.*;
import  net.postgis.jdbc.PGgeometry;
import  net.postgis.jdbc.geometry.Point;
import  java.io.*;



/**
 * Database transaction. 
 */
 
public class TrackerDBSession extends DBSession
{
    private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
    private ServerAPI _api;
       
   
    public TrackerDBSession (DataSource dsrc, ServerAPI api, boolean autocommit, Logfile log)
       throws DBSession.SessionError
    {
       super(dsrc, autocommit, log); 
       _api = api; 
    }
   
   
   
    public TrackerDBSession(DBSession s) 
    {
       super(s);
       _api = s._api;
    }
   
   
       
       
    /**
     * Get geographical point from PostGIS. 
     * Convert it to jcoord LatLng reference. 
     */  
    private static LatLng getRef(ResultSet rs, String field)
       throws java.sql.SQLException
    {
        PGgeometry geom = (PGgeometry) rs.getObject(field);
        Point pt = (Point) geom.getGeometry();
        return new LatLng(pt.y, pt.x);
    }
      


       
 
    /**
     * Add managed tracker to the database.
     */
    public void addTracker(String id, String user, String alias, String icon)  
            throws java.sql.SQLException
    {
        _log.debug("MyDbSession", "addTracker: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"Tracker\" (id, userid, alias, icon)" + 
                " VALUES (?, ?, ?, ?)" );
         stmt.setString(1, id);
         stmt.setString(2, user);
         stmt.setString(3, alias);
         stmt.setString(4, icon);
         stmt.executeUpdate();
    }
    
    
    public void updateTracker(String id, String user, String alias, String icon)
            throws java.sql.SQLException
    {
        _log.debug("MyDbSession", "updateTracker: "+id);
        PreparedStatement stmt = getCon().prepareStatement
            ( "UPDATE \"Tracker\" SET alias=?, icon=? "+(user!= null ? ", userid=? ":"") +
              "WHERE id=?" );
        stmt.setString(1, alias);
        stmt.setString(2, icon);
        if (user!=null) { 
            stmt.setString(3, user);
            stmt.setString(4, id);
        }
        else
            stmt.setString(3, id);
        stmt.executeUpdate();
    }  
    
    
    
    public int deleteTracker(String id)
            throws java.sql.SQLException
    {
        _log.debug("MyDbSession", "deleteTracker: "+id);
        PreparedStatement stmt = getCon().prepareStatement
            ( " DELETE FROM \"Tracker\" "+
              " WHERE id=?; ");
        stmt.setString(1, id);
        return stmt.executeUpdate();
    }
         
    
    
    public Tracker getTracker(String id)
        throws java.sql.SQLException
    {      
        _log.debug("MyDbSession", "getTracker: "+id);
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"Tracker\" "  +
              " WHERE id=?", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, id);
        return new DbList<Tracker> ( stmt.executeQuery(), rs->
           { return new Tracker(_api.getDB(), rs.getString("id"), rs.getString("userid"), rs.getString("alias"), rs.getString("icon")); }
        ).next();
    }
    
    

    public DbList<Tracker> getTrackers(String user)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT id, alias, icon FROM \"Tracker\"" +
              " WHERE userid=? ORDER BY id ASC", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, user);
        return new DbList<Tracker>( stmt.executeQuery(), rs ->
            { return new Tracker(_api.getDB(), rs.getString("id"), user, rs.getString("alias"), rs.getString("icon"));  }
        );
    }
     
     
    public DbList<String> getTrackerTags(String id)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT tag FROM \"TrTags\" t, \"Tracker\" tr " +
              " WHERE t.userid=tr.userid AND id=? ORDER BY tag ASC", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, id);
        return new DbList<String>( stmt.executeQuery(), rs ->
            { return rs.getString("tag"); }
        );
    }
    
    
    public DbList<String> getTrackerTagsUser(String id)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT tag FROM \"TrTags\" t " +
              " WHERE userid=? ORDER BY tag ASC", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, id);
        return new DbList<String>( stmt.executeQuery(), rs ->
            { return rs.getString("tag"); }
        );
    }
    
    
    public void addTrackerTag(String user, String tag)  
            throws java.sql.SQLException
    {
        _log.debug("MyDbSession", "addTrackerTag: "+user);
         PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"TrTags\" (userid, tag)" + 
                " VALUES (?, ?)" );
         stmt.setString(1, user);
         stmt.setString(2, tag);
         stmt.executeUpdate();
    }
    
    
    
    public int deleteTrackerTag(String user, String tag)
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " DELETE FROM \"TrTags\" "+
              " WHERE userid=? AND tag=?; ");
        stmt.setString(1, user);
        stmt.setString(2, tag);
        return stmt.executeUpdate();
    }
    
    
    
    
    
}

