 
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
 
public class SignsDBSession extends DBSession
{
    private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
    private ServerAPI _api;
       
   
    public SignsDBSession (DataSource dsrc, ServerAPI api, boolean autocommit, Logfile log)
       throws DBSession.SessionError
    {
       super(dsrc, autocommit, log); 
       _api = api; 
    }
   
   
   
    public SignsDBSession(DBSession s) 
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
        if (geom==null)
            return null;
        Point pt = (Point) geom.getGeometry();
        return new LatLng(pt.y, pt.x);
    }
      


    
    public String addSignIdent(String id, long maxscale, String icon, String url, String descr, LatLng pos, int cls, String uid)
            throws java.sql.SQLException
    {
        _log.debug("SignsDbSession", "addSignIdent: "+descr+", class="+cls);
         PreparedStatement stmt = getCon().prepareStatement
              ( "INSERT INTO \"Signs\" (id, maxscale, icon, url, description, position, class, userid)" + 
                "VALUES ( ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id" );
         stmt.setString(1, id);
         stmt.setLong(2, maxscale);
         stmt.setString(3, icon);
         stmt.setString(4, url);
         stmt.setString(5, descr);
         setRef(stmt, 6, pos);
         stmt.setInt(7, cls);
         stmt.setString(8, uid);
         ResultSet rs = stmt.executeQuery(); 
         rs.next();
         return rs.getString("id");
    }
    
    
    
    public String addSign(String srvid, long maxscale, String icon, String url, String descr, LatLng pos, int cls, String uid)
            throws java.sql.SQLException
    {
         _log.debug("SignsDbSession", "addSign: "+descr+", class="+cls);
         PreparedStatement stmt = getCon().prepareStatement
              ( "INSERT INTO \"Signs\" (id, maxscale, icon, url, description, position, class, userid)" + 
                "VALUES ( nextval('signs_seq') || '@" + srvid + "', ?, ?, ?, ?, ?, ?, ?) RETURNING id" );
         stmt.setLong(1, maxscale);
         stmt.setString(2, icon);
         stmt.setString(3, url);
         stmt.setString(4, descr);
         setRef(stmt, 5, pos);
         stmt.setInt(6, cls);
         stmt.setString(7, uid);
         ResultSet rs = stmt.executeQuery(); 
         rs.next();
         return rs.getString("id");
    }
    
    
    public String addSign(String srvid, long maxscale, String icon, String url, String descr, LatLng pos, int cls)
        throws java.sql.SQLException
    { return addSign(srvid, maxscale, icon, url, descr, pos, cls, null); }
    
    
    
    public void updateSign(String id, long maxscale, String icon, String url, String descr, LatLng pos, int cls, String uid)
            throws java.sql.SQLException
    {
        _log.debug("SignsDbSession", "updateSign: "+id+", "+descr);
        PreparedStatement stmt = getCon().prepareStatement
            ( "UPDATE \"Signs\" SET maxscale=?, position=?, icon=?, url=?, description=?, class=?"+
              "WHERE id=?;" +
              "UPDATE \"Signs\" SET userid=? WHERE id=? AND userid IS NULL");
        stmt.setLong(1, maxscale);
        setRef(stmt, 2, pos);
        stmt.setString(3, icon);
        stmt.setString(4, url);
        stmt.setString(5, descr);
        stmt.setInt(6, cls);      
        stmt.setString(7, id);
        stmt.setString(8, uid);
        stmt.setString(9, id);
        stmt.executeUpdate();
    }  
    public void updateSign(String id, long maxscale, String icon, String url, String descr, LatLng pos, int cls)
        throws java.sql.SQLException
    { updateSign(id, maxscale, icon, url, descr, pos, cls, null); }
    
    
    
    public Sign getSign(String id)
          throws java.sql.SQLException
    {
         _log.debug("SignsDbSession", "getSign: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT s.id AS sid, position, maxscale, url, description, cl.name AS cname, "+
                " s.icon AS sicon, cl.icon AS cicon, class, userid " +
                " FROM \"Signs\" s LEFT JOIN \"SignClass\" cl ON s.class=cl.id " +
                 "WHERE s.id=?" );
         stmt.setString(1, id);
         ResultSet rs = stmt.executeQuery();
         if (rs.next()) {
            String icon = rs.getString("sicon");
            if (icon == null)
                icon = rs.getString("cicon");
                    
            // Item (Reference r, long sc, String ic, String url, String txt)
            return new Sign(rs.getString("sid"), getRef(rs, "position"), rs.getLong("maxscale"), icon,
                    rs.getString("url"), rs.getString("description"), rs.getInt("class"), rs.getString("cname"), rs.getString("userid")  ); 
         }
         return null;
    }
    

    
    /**
     * Get list of signs in a specified geographic area and above a specified scale 
     */
    public DbList<Signs.Item> getSigns(long scale, LatLng uleft, LatLng lright)
       throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT s.id AS sid, position, maxscale, url, description, cl.name, s.icon AS sicon, cl.icon AS cicon " +
             " FROM \"Signs\" s LEFT JOIN \"SignClass\" cl ON s.class=cl.id" +
             " WHERE maxscale>=? AND position && ST_MakeEnvelope(?, ?, ?, ?, 4326) AND NOT s.hidden"+
             " LIMIT 300",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT );
        stmt.setLong(1, scale);
        stmt.setDouble(2, uleft.getLng());
        stmt.setDouble(3, uleft.getLat());
        stmt.setDouble(4, lright.getLng());
        stmt.setDouble(5, lright.getLat());
        stmt.setMaxRows(300);
         
        return new DbList<Signs.Item>(stmt.executeQuery(), rs -> 
            {
                String icon = rs.getString("sicon");
                if (icon == null)
                    icon = rs.getString("cicon");

                return new Signs.Item(rs.getString("sid"), getRef(rs, "position"), 0, icon,
                    rs.getString("url"), rs.getString("description"));  
            });
    }
    
    
    
    
    /**
     * Get list of signs 
     */
    public DbList<Sign> getAllSigns(int type, String user)
       throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT s.id AS sid, position, maxscale, url, description, cl.name AS cname, "+
             " s.icon AS sicon, cl.icon AS cicon, class " +
             " FROM \"Signs\" s LEFT JOIN \"SignClass\" cl ON s.class=cl.id" +
             " WHERE true"+
             (type > -1 ? " AND class="+type : "") + 
             (user != null ? " AND userid='"+user+"'" : "") + 
             " LIMIT 5000",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT ); 
        stmt.setMaxRows(5000);
         
        return new DbList<Sign>(stmt.executeQuery(), rs -> 
            {
                String icon = rs.getString("sicon");
                if (icon == null)
                    icon = rs.getString("cicon");

                // Item (Reference r, long sc, String ic, String url, String txt)
                return new Sign(rs.getString("sid"), getRef(rs, "position"), rs.getLong("maxscale"), icon,
                    rs.getString("url"), rs.getString("description"), rs.getInt("class"), rs.getString("cname")   );  
            });
    }
    
        
        
    public int deleteSign(String id)
          throws java.sql.SQLException
    {
         _log.debug("SignsDbSession", "deleteSign: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( "DELETE FROM \"Signs\"" + 
                "WHERE id=?" );
         stmt.setString(1, id);
         return stmt.executeUpdate();
    }
    
    
    
    public DbList<Sign.Category> getCategories()
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"SignClass\" ORDER BY name ASC ", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
         
        return new DbList<Sign.Category>(stmt.executeQuery(), rs ->
            { return new Sign.Category(rs.getInt("id"), rs.getString("name"), rs.getString("icon")); });
    }
        
        
    
    public Photo getPhoto(String id, String user)
          throws java.sql.SQLException
    {
         _log.debug("SignsDbSession", "getSign: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT *" +
                " FROM \"Photo\""+
                " WHERE id=?"+
                " AND userid=?" ); // FIXME: Allow sharing
         stmt.setString(1, id);
         stmt.setString(2, user);
         
         ResultSet rs = stmt.executeQuery();
         if (rs.next()) {
            return new Photo(rs.getString("id"), rs.getTimestamp("time"), 
               getRef(rs, "position"), rs.getString("userid"), rs.getString("descr") ); 
         }
         return null;
    }
        
        
    
    public String addPhoto(String srvid, LatLng pos, String user, java.util.Date time, String descr, byte[] image)
            throws java.sql.SQLException
    {
         _log.debug("SignsDbSession", "addPhoto: "+descr);
         PreparedStatement stmt = getCon().prepareStatement
              ( "INSERT INTO \"Photo\" (id, userid, time, descr, position, image)" + 
                "VALUES ( nextval('signs_seq') || '@" + srvid + "', ?, ?, ?, ?, ?) RETURNING id" );
         
         stmt.setString(1, user);
         stmt.setTimestamp(2, date2ts(time));
         stmt.setString(3, descr);
         setRef(stmt, 4, pos);
         stmt.setBytes(5, image);
         ResultSet rs = stmt.executeQuery(); 
         rs.next();
         return rs.getString("id");
    }    
    
    
    
    public DbList<Signs.Item> getPhotos(String user, java.util.Date time, LatLng uleft, LatLng lright)
       throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT *" +
             " FROM \"Photo\""+
             " WHERE userid=? AND time <= ? AND " +                // FIXME: Add timespan
             "   position && ST_MakeEnvelope(?, ?, ?, ?, 4326) " + // FIXME: Allow sharing
             " LIMIT 300", 
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT );
        stmt.setString(1, user);
        if (time==null)
            time = new java.util.Date();
        
        stmt.setTimestamp(2, date2ts(time));
        stmt.setDouble(3, uleft.getLng());
        stmt.setDouble(4, uleft.getLat());
        stmt.setDouble(5, lright.getLng());
        stmt.setDouble(6, lright.getLat());
        stmt.setMaxRows(300);
        
        return new DbList<Signs.Item>(stmt.executeQuery(), rs -> 
            {
                return new Signs.Item(rs.getString("id"), getRef(rs, "position"), 0, 
                    "signs/camera.png", "P", rs.getString("descr"));  
            });
    }
    
    
            
        
    public int deletePhoto(String id, String user)
          throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
              ( "DELETE FROM \"Photo\"" + 
                "WHERE id=? AND userid=?" );
         stmt.setString(1, id);
         stmt.setString(2, user);
         return stmt.executeUpdate();
    }
    
    
        
    public int setSeqNext(String seq, int next) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT setval(?, ?, false)" );
        stmt.setString(1, seq);
        stmt.setInt(2, next);
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
             return rs.getInt("setval");
        return -1;
    }
    
    
}

