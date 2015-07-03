/* 
 * Copyright (C) 2014 by Øyvind Hanssen (ohanssen@acm.org)
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
import  java.text.*;
import  java.sql.*;
import  javax.sql.*;
import  java.util.concurrent.locks.*; 
import  org.apache.commons.dbcp.*; 
import  uk.me.jstott.jcoord.*;
import  no.polaric.aprsd.*;
import  org.postgis.PGgeometry;



/**
 * Database transaction. 
 */
 
public class MyDBSession extends DBSession
{
   
   private ServerAPI _api; 
   private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
   
   MyDBSession (DataSource dsrc, ServerAPI api, boolean autocommit, Logfile log)
   {
      super(dsrc, autocommit, log); 
      _api = api; 
   }
   
   
       
       
    /**
     * Get geographical point from PostGIS. 
     * Convert it to jcoord LatLng reference. 
     */  
    private Reference getRef(ResultSet rs, String field)
       throws java.sql.SQLException
    {
        PGgeometry geom = (PGgeometry) rs.getObject(field);
        org.postgis.Point pt = (org.postgis.Point) geom.getGeometry();
        return new LatLng(pt.y, pt.x);
    }
      
      
    /**
      * Encode and add a position to a PostGIS SQL statement.
      */
    private void setRef(PreparedStatement stmt, int index, Reference pos)
       throws SQLException 
    {
        LatLng ll = pos.toLatLng();
        org.postgis.Point p = new org.postgis.Point( ll.getLng(), ll.getLat() );
        p.setSrid(4326);
        stmt.setObject(index, new PGgeometry(p));
    }
      

  
  
  
    /**
     * Get points that were transmitted via a certain digipeater during a certain time span. 
     */
    public synchronized DbList<TPoint> getPointsVia(String digi, Reference uleft, Reference lright, java.util.Date from, java.util.Date to)
       throws java.sql.SQLException
    {
        _log.log(" getPointsVia: "+digi+", "+df.format(from)+" - "+df.format(to));
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT DISTINCT position "+ 
             " FROM \"AprsPacket\" p, \"PosReport\" r " +
             " WHERE  p.src=r.src " +
             " AND  p.time=r.rtime " + 
             " AND  (substring(p.path, '([^,\\*]+).*\\*.*')=? OR " +
                     " (substring(p.ipath, 'qAR,([^,\\*]+).*')=? AND p.path !~ '.*\\*.*')) " +
             " AND  position && ST_MakeEnvelope(?, ?, ?, ?, 4326) " +
             " AND  p.time > ? AND p.time < ? LIMIT 10000",

             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
         stmt.setString(1, digi);
         stmt.setString(2, digi);

         LatLng ul = uleft.toLatLng();
         LatLng lr = lright.toLatLng();
         stmt.setDouble(3, ul.getLng());
         stmt.setDouble(4, ul.getLat());
         stmt.setDouble(5, lr.getLng());
         stmt.setDouble(6, lr.getLat());

         stmt.setTimestamp(7, date2ts(from));
         stmt.setTimestamp(8, date2ts(to));
         stmt.setMaxRows(10000);
         ResultSet rs = stmt.executeQuery();
         DbList<TPoint> list = new DbList(rs, new DbList.Factory() 
         {
                public TPoint getElement(ResultSet rs) throws SQLException 
                {
                   return new TPoint(null, getRef(rs, "position"));  
                }
            });
         return list;
    }

    
    
    public synchronized void addSign(long maxscale, String icon, String url, String descr, Reference pos, int cls)
            throws java.sql.SQLException
    {
         _log.log(" addSign: "+descr+", class="+cls);
         PreparedStatement stmt = getCon().prepareStatement
              ( "INSERT INTO \"Signs\" (maxscale, icon, url, description, position, class)" + 
                "VALUES (?, ?, ?, ?, ?, ?)" );
         stmt.setLong(1, maxscale);
         stmt.setString(2, icon);
         stmt.setString(3, url);
         stmt.setString(4, descr);
         setRef(stmt, 5, pos);
         stmt.setInt(6, cls);
         stmt.executeUpdate();
    }
    
    
    public synchronized void updateSign(int id, long maxscale, String icon, String url, String descr, Reference pos, int cls)
            throws java.sql.SQLException
    {
        _log.log(" updateSign: "+id+", "+descr);
        PreparedStatement stmt = getCon().prepareStatement
            ( "UPDATE \"Signs\" SET maxscale=?, position=?, icon=?, url=?, description=?, class=?"+
              "WHERE id=?" );
        stmt.setLong(1, maxscale);
        setRef(stmt, 2, pos);
        stmt.setString(3, icon);
        stmt.setString(4, url);
        stmt.setString(5, descr);
        stmt.setInt(6, cls);
        stmt.setInt(7, id);
        stmt.executeUpdate();
    }  
    
    
    public synchronized Sign getSign(int id)
          throws java.sql.SQLException
    {
         _log.log(" getSign: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( "SELECT * FROM \"Signs\"" + 
                "WHERE id=?" );
         stmt.setInt(1, id);
         ResultSet rs = stmt.executeQuery();
         if (rs.next()) 
            return new Sign(rs.getInt("id"), getRef(rs, "position"), rs.getLong("maxscale"), rs.getString("icon"),
                 rs.getString("url"), rs.getString("description"), rs.getInt("class"));
         return null;
    }
    
    
        
    public synchronized void deleteSign(int id)
          throws java.sql.SQLException
    {
         _log.log(" deleteSign: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( "DELETE FROM \"Signs\"" + 
                "WHERE id=?" );
         stmt.setInt(1, id);
         stmt.executeUpdate();
    }
    
    
    
    /**
     * Get list of signs in a specified geographic area and above a specified scale 
     */
    public synchronized DbList<Signs.Item> getSigns(long scale, Reference uleft, Reference lright)
       throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT s.id AS sid, position, maxscale, url, description, cl.name, s.icon AS sicon, cl.icon AS cicon " +
             " FROM \"Signs\" s LEFT JOIN \"SignClass\" cl ON s.class=cl.id" +
             " WHERE maxscale>=? AND position && ST_MakeEnvelope(?, ?, ?, ?, 4326) "+
             " LIMIT 200",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT );
         stmt.setLong(1, scale);
         LatLng ul = uleft.toLatLng();
         LatLng lr = lright.toLatLng();
         stmt.setDouble(2, ul.getLng());
         stmt.setDouble(3, ul.getLat());
         stmt.setDouble(4, lr.getLng());
         stmt.setDouble(5, lr.getLat());
         stmt.setMaxRows(200);
         ResultSet rs = stmt.executeQuery();
         DbList<Signs.Item> list = new DbList(rs, new DbList.Factory() 
         {
                public Signs.Item getElement(ResultSet rs) throws SQLException 
                { 
                   String icon = rs.getString("sicon");
                   if (icon == null)
                      icon = rs.getString("cicon");

                  // Item (Reference r, long sc, String ic, String url, String txt)
                   return new Signs.Item(rs.getInt("sid"), getRef(rs, "position"), 0, icon,
                           rs.getString("url"), rs.getString("description"));  
                }
            });
         return list;
    }
    
    
    
    public synchronized DbList<Sign.Category> getCategories()
        throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"SignClass\" ORDER BY name ASC ");
         ResultSet rs = stmt.executeQuery();
         DbList<Sign.Category> list = new DbList(rs, new DbList.Factory() 
         {
              public Sign.Category getElement(ResultSet rs) throws SQLException { 
                 return new Sign.Category(rs.getInt("id"), rs.getString("name"), rs.getString("icon"));  
              }
         });
         return list;   
    }
    
    
    
    /* FIXME: should true be default? */
    public DbList<TPoint> getTrail(String src, java.util.Date from, java.util.Date to)
          throws java.sql.SQLException
       { return getTrail(src,from,to,true); }
                                                                           
    /**
     * Get trail for a given station and a given time span. 
     */
    public synchronized DbList<TPoint> getTrail(String src, java.util.Date from, java.util.Date to, boolean rev)
       throws java.sql.SQLException
    {
        _log.log(" getTrail: "+src+ ", "+df.format(from)+" - "+df.format(to));
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT * FROM \"PosReport\"" +
             " WHERE src=? AND time >= ? AND time <= ?" + 
             " ORDER BY time "+(rev? "DESC" : "ASC")+" LIMIT 500",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
         stmt.setString(1, src);
         stmt.setTimestamp(2, date2ts(from));
         stmt.setTimestamp(3, date2ts(to));
         stmt.setMaxRows(500);
         ResultSet rs = stmt.executeQuery();
         DbList<TPoint> list = new DbList(rs, new DbList.Factory() 
         {
                public TPoint getElement(ResultSet rs) throws SQLException 
                {
                   return new TPoint(rs.getTimestamp("time"), getRef(rs, "position"));  
                }
            });
         return list;
    }
    

    
    
   /**
     * Get trail poiint for a given station and a given time. 
     */
    public synchronized Trail.Item getTrailPoint(String src, java.util.Date t)
       throws java.sql.SQLException
    { 
       _log.log(" getTrailPoint: "+src+", "+df.format(t));
       /* Left outer join with AprsPacket to get path where available */
       PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT pr.time, position, speed, course, path, ipath, nopkt FROM \"PosReport\" AS pr" +
             " LEFT JOIN \"AprsPacket\" AS ap ON pr.src = ap.src AND pr.rtime = ap.time "+
             " WHERE pr.src=? AND pr.time > ? AND pr.time < ?",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
       stmt.setString(1, src);
       stmt.setTimestamp(2, date2ts(t, -1200));
       stmt.setTimestamp(3, date2ts(t, +1200));
       ResultSet rs = stmt.executeQuery();
       if (rs.first()) {
          String p = ""; 
          if (rs.getBoolean("nopkt")) 
             p = "(ext)";
          else {
              String path = rs.getString("path");
              String ipath = rs.getString("ipath");
              if (path == null)
                  p = "?"; 
              else {
                  p = path; 
                  if (ipath != null && ipath.length() > 1)
                      p = p + (p.length() > 1 ? "," : "") + ipath;
              }
          }
          
          return new Trail.Item(rs.getTimestamp("time"), getRef(rs, "position"), 
                         rs.getInt("speed"), rs.getInt("course"), p );  
       }
       else
          return null;
    }     
     
     
     
    /**
     * Get an APRS item at a given point in time.
     */    
    public synchronized AprsPoint getItem(String src, java.util.Date at)
       throws java.sql.SQLException
    {
        _log.log(" getItem:  "+src+", "+df.format(at));
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT * FROM \"PosReport\"" +
             " WHERE src=? AND time <= ?" + 
             " ORDER BY time DESC LIMIT 1",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, src);
        stmt.setTimestamp(2, new Timestamp(at.getTime()));
        ResultSet rs = stmt.executeQuery();
        
                
        String name[] = src.split("@",2);
        AprsPoint x = null;
        if (name.length>1) {
            Station owner = _api.getDB().getStation(name[1], null);
            x = new AprsObject(owner, name[0]);
        } else
            x = new Station(src);
        
        if (rs.next()) 
           x.update(rs.getDate("time"), 
             new AprsHandler.PosData(getRef(rs, "position"), rs.getInt("course"), rs.getInt("speed"), 
                 rs.getString("symbol").charAt(0), rs.getString("symtab").charAt(0) ),
             null, null);  
        return x; 
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
    public synchronized DbList<Mission> searchMissions(java.util.Date at, java.util.Date until, String src, String alias )
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
    public synchronized Mission getMission(String src, java.util.Date at)
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

    public synchronized void endMission(String src, java.util.Date at) throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
           ( "UPDATE \"Mission\" SET end=? WHERE src=?" );
         stmt.setString(1, src);  
         if (at == null)
            stmt.setTimestamp(2, new Timestamp(System.currentTimeMillis() ) );
         else
            stmt.setTimestamp(2, date2ts(at));
            
    }
    
    public synchronized void endMission(String src) throws java.sql.SQLException
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
    public synchronized Mission assignMission(Station st, String alias, String icon, 
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
    public synchronized void addMission(String src, String alias, String icon,  
            java.util.Date start,  java.util.Date end, String descr)
            throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
              ( "INSERT INTO \"Mission\" (src, alias, icon, start, end, descr)" + 
                "VALUES (?, ?, ?, ?, ?)" );
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
     
     
     
}

