/* 
 * Copyright (C) 2025 by LA7ECA, Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */
 
package no.polaric.aprsdb;
import  no.polaric.core.*;
import  no.polaric.aprsd.*;
import  no.polaric.aprsd.point.*;
import  java.text.*;
import  java.sql.*;
import  javax.sql.*;
import  java.util.concurrent.locks.*; 
import  net.postgis.jdbc.PGgeometry;
import  net.postgis.jdbc.geometry.Point;
import  java.io.*;



/**
 * Database transaction. 
 */
 
public class SarDBSession extends DBSession
{
   
   private ServerConfig _api; 
   private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
   
   SarDBSession (DataSource dsrc, ServerConfig api, boolean autocommit, Logfile log)
    throws DBSession.SessionError
   {
      super(dsrc, autocommit, log); 
      _api = api; 
   }
   
   
       
       
    /**
     * Get geographical point from PostGIS. 
     * Convert it to jcoord LatLng reference. 
     */  
    private LatLng getRef(ResultSet rs, String field)
       throws java.sql.SQLException
    {
        PGgeometry geom = (PGgeometry) rs.getObject(field);
        Point pt = (Point) geom.getGeometry();
        return new LatLng(pt.y, pt.x);
    }
      

  


    
    /**
     * Return a list of missions active at a given time. Optionally, we may add filters
     * on id or alias (regular expressions). If the 'at' argument is null this means now.
     * If the until argument is non-null and after 'at', this means we search for missions
     * active in the time span from 'at' to 'until'. 
     *
     * @param at the given time
     * @param until the end of the time interval to search for. May be null. 
     * @param src   Regular expression to filter for callsigns/identifier. May be null.
     * @param alias Regular expression to filter for alias. May be null.
     */
    public DbList<Mission> searchMissions(java.util.Date at, java.util.Date until, String src, String alias )
    { 
      /* TBD */
      return null;
    }
    
    
    
    /**
     * Return the mission that was (or is going to be) active for a station at a 
     * given time. 
     * If time is null, return the mission currently active. 
     *
     * @param src Source callsign (or identifier)
     * @param at  Time when the mission (that we search for) is active. 
     */
    public Mission getMission(String src, java.util.Date at)
       throws java.sql.SQLException
    {
       PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT src,alias,icon,start,end,descr FROM \"Mission\"" +
             " WHERE src=? AND time = ?", 
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
       stmt.setString(1, src);
       stmt.setTimestamp(2, date2ts(at));
       ResultSet rs = stmt.executeQuery();  
       if (rs.next())
          return new Mission(rs.getString("src"), rs.getString("alias"), rs.getString("icon"), 
                      rs.getTimestamp("start"), rs.getTimestamp("end"), rs.getString("descr")); 
       else
          return null;
    }
    
    
    
    /**
     * Set end time for a mission. 
     * If argument at is null or is missing, use time now. 
     */

    public void endMission(String src, java.util.Date at) throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
           ( "UPDATE \"Mission\" SET end=? WHERE src=?" );
         stmt.setString(1, src);  
         if (at == null)
            stmt.setTimestamp(2, new Timestamp(System.currentTimeMillis() ) );
         else
            stmt.setTimestamp(2, date2ts(at));
            
    }
    
    public void endMission(String src) throws java.sql.SQLException
      { endMission(src, null); }
      
      
    /**
     * Assign a mission to a station.
     * A mission has a start and an end time. If end-time is not set (null),
     * or in the future, the mission is active. An active mission may
     * set the alias and the icon of the station (if defined). 
     *
     * @param st  Station to whom we assign the mission
     * @param alias Alias to be used with the station during the mission
     * @param icon Icon to be used with the station during the mission
     * @param start Time when mission starts
     * @param end   Time when mission ends. May be null (open).
     * @param descr Text that describes the mission
     */
    public Mission assignMission(Station st, String alias, String icon, 
            java.util.Date start,  java.util.Date end, String descr)
            throws java.sql.SQLException
    {
       addMission(st.getIdent(), alias, icon, start, end, descr);
       return new Mission(st.getIdent(), alias, icon, start, end, descr);  
       
    }
    
    
    
    /**
     * Add a mission to the database.
     *
     * @param src Source callsign (or identifier)
     * @param alias Alias to be used with the callsign during the mission
     * @param icon Icon to be used with the callsign during the mission
     * @param start Time when mission starts
     * @param end   Time when mission ends. May be null (open).
     * @param descr Text that describes the mission
     * 
     */
    public void addMission(String src, String alias, String icon,  
            java.util.Date start,  java.util.Date end, String descr)
            throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"Mission\" (src, alias, icon, start, end, descr)" + 
                " VALUES (?, ?, ?, ?, ?)" );
         stmt.setString(1, src);
         stmt.setString(2, alias);
         stmt.setString(3, icon);
         stmt.setTimestamp(4, date2ts(start));
         if (end == null)
            stmt.setNull(5, java.sql.Types.TIMESTAMP);
         else
            stmt.setTimestamp(5, date2ts(end));
         stmt.setString(6, descr);
         stmt.executeUpdate();
    }
     
 
    
    
    /*
    
   
    public synchronized DbList<HistSearch> getHistSearch(String user)
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * FROM \"HistSearch\"" +
              " WHERE userid=? ORDER BY name ASC", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
            stmt.setString(1, user);
            
            return new DbList( stmt.executeQuery(), rs ->
                { return new HistSearch(rs.getString("name"), rs.getString("src"),
                               rs.getTimestamp("tstart"), rs.getTimestamp("tend") );  }
            );
    }
    
    
    
    
    public synchronized void addHistSearch(String user, HistSearch hs) 
            throws java.sqlException
    {
        deleteHistSearch(hs);
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"HistSearch\" (userid, name, src, tstart, tend)" + 
                " VALUES (?, ?, ?, ?, ?)" );
        stmt.setString(1, user);
        stmt.setString(2, hs.name);
        stmt.setString(3, hs.src);
        stmt.setTimestamp(4, hs.tstart);
        stmt.setTimestamp(5, hs.tend);
        stmt.executeUpdate();
    }
    

    
    
    public synchronized void deleteHistSearch(String user, HistSearch hs) 
            throws java.sqlException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " DELETE FROM \"HistSearch\" 
              " WHERE userid=? AND name=? AND src=? AND tstart=?" );
        stmt.setString(1, user);
        stmt.setString(2, hs.name);
        stmt.setString(3, hs.src);
        stmt.setTimestamp(4, hs.tstart);
        stmt.executeUpdate();
    }
    
    */
    
}

