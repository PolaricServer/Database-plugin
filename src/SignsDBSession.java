 
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
    
    
    
    
    public int deleteSignsByUser(String userid)
        throws java.sql.SQLException
    {
        _log.debug("SignsDbSession", "deleteSignsByUser: "+userid);
        PreparedStatement stmt = getCon().prepareStatement
              ( "DELETE FROM \"Signs\"" + 
                "WHERE userid=?" );
         stmt.setString(1, userid);
         return stmt.executeUpdate();
    }
    
    
    
    /**
     * Get list of signs (just the id's) owned by a specific user 
     */
    public DbList<String> getSignsByUser(String userid)
       throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT id FROM \"Signs\" " +
             " WHERE userid=?",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, userid);
        
        return new DbList<String>(stmt.executeQuery(), rs -> {
                return rs.getString("id");  
            });
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
        
        
    
    public Photo getPhoto(String id, String user, String group)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT a.id as id, time, position, p.userid as owner, descr, image FROM \"Photo\" p, \"ObjectAccess\" a" +
              " WHERE p.id=a.id AND (a.userid=? OR a.userid='#ALL' OR a.userid=?) AND a.id=?", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT );
        stmt.setString(1, (user==null ? "NO-USER" : user));
        stmt.setString(2, "@"+(group==null ? "NOLOGIN": group));
        stmt.setString(3, id);
        
        ResultSet rs = stmt.executeQuery();
        if (rs.next()) {
            return new Photo(rs.getString("id"), rs.getTimestamp("time"), 
               getRef(rs, "position"), rs.getString("owner"), rs.getString("descr"), rs.getBytes("image") ); 
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
         String objid = rs.getString("id");
         
         /* Add user access to the object */
         PreparedStatement stmt2 = getCon().prepareStatement
              ( " INSERT INTO \"ObjectAccess\" (id, userid, photo)" +
                " VALUES (?, ?, 'true')" );
         stmt2.setString(1, objid);
         stmt2.setString(2, user);
         stmt2.executeUpdate();
         return objid;
    }    
    
    
    
    public void updatePhotoImg(String id, byte[] image)
            throws java.sql.SQLException
    {
         _log.debug("SignsDbSession", "updatePhotoImg: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( "UPDATE \"Photo\" SET image=? WHERE id=?" );
         
         stmt.setBytes(1, image);
         stmt.setString(2, id);
         stmt.executeUpdate();
    } 
    
    
    
    public void updatePhotoDescr(String id, String userid, String descr)
            throws java.sql.SQLException
    {
         _log.debug("SignsDbSession", "updatePhotoDescr: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( "UPDATE \"Photo\" SET descr=? WHERE id=? AND userid=?" );
         
         stmt.setString(1, descr);
         stmt.setString(2, id);
         stmt.setString(3, userid);
         stmt.executeUpdate();
    }
    
    
    public DbList<Signs.Item> getPhotos(String user, String group, java.util.Date time, LatLng uleft, LatLng lright)
          throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT DISTINCT ON (a.id) a.id as id, p.userid as uid, time, position, descr" +
              " FROM \"Photo\" p, \"ObjectAccess\" a " +
              " WHERE a.id=p.id AND photo=true AND (a.userid=?  OR a.userid='#ALL' OR a.userid=?) AND " +
              "    time <= ? AND time + interval '1 month' >= ? AND " +       
              "    position && ST_MakeEnvelope(?, ?, ?, ?, 4326) " +
              " LIMIT 300" +
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT );
        
        if (time==null)
            time = new java.util.Date();
            
         stmt.setString(1, (user==null ? "_NO-USER_" : user));
         stmt.setString(2, "@"+(group==null ? "NOLOGIN": group));
         stmt.setTimestamp(3, date2ts(time));
         stmt.setTimestamp(4, date2ts(time));
         stmt.setDouble(5, uleft.getLng());
         stmt.setDouble(6, uleft.getLat());
         stmt.setDouble(7, lright.getLng());
         stmt.setDouble(8, lright.getLat());
         stmt.setMaxRows(300);
        
        return new DbList<Signs.Item>(stmt.executeQuery(), rs ->
            {
                var x = new Photo(rs.getString("id"), rs.getTimestamp("time"), getRef(rs, "position"),
                    rs.getString("uid"), rs.getString("descr"));
                return x;
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
    
    
                    
    public int unlinkPhoto(String ident, String owner, String userid)
            throws java.sql.SQLException
    {
        /* First, remove links from users where owner has a non-readonly link */
        PreparedStatement stmt = getCon().prepareStatement
              ( " DELETE FROM \"ObjectAccess\" WHERE id=? "+
                " AND userid=? AND photo='true' "+
                " AND EXISTS " +
                "   ( SELECT id FROM \"ObjectAccess\" " + 
                "     WHERE id=? AND userid=? AND photo='true' AND readonly='false' ) " 
              );
        stmt.setString(1, ident);
        stmt.setString(2, userid);
        stmt.setString(3, ident);
        stmt.setString(4, owner);
        stmt.executeUpdate();
        
        /* Remove dangling @group and #ALL links */
        stmt = getCon().prepareStatement
              ( " DELETE FROM \"ObjectAccess\" WHERE id=? AND photo='true' AND userid ~ '[@#].+' "+
                " AND NOT EXISTS "+
                "    ( SELECT id FROM \"ObjectAccess\" WHERE id=? AND photo='true' AND NOT userid ~ '[@#].+') " );
        stmt.setString(1,ident);
        stmt.setString(2, ident);
        stmt.executeUpdate();
        
        /* Now if no user-links left, remove JsObject */
        stmt = getCon().prepareStatement
              ( " DELETE FROM \"Photo\" WHERE id=? AND NOT EXISTS "+
                "  ( SELECT id FROM \"ObjectAccess\" where id=? AND photo='true') ");
        stmt.setString(1, ident);
        stmt.setString(2, ident);
        return stmt.executeUpdate();
    }
    
    
    
    public void sharePhoto(String ident, String owner, String userid, boolean readonly)
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"ObjectAccess\" (id, readonly, userid, photo)" +
                " SELECT id, ?, ?, 'true' "+
                " FROM \"Photo\" NATURAL JOIN \"ObjectAccess\" "+
                "   WHERE id=? AND userid=? AND readonly=false AND NOT EXISTS "+
                "       (SELECT userid FROM \"ObjectAccess\" WHERE id=? AND userid=?) limit 1");
        stmt.setBoolean(1, readonly);
        stmt.setString(2, userid);
        stmt.setString(3, ident);
        stmt.setString(4, owner);
        stmt.setString(5, ident);
        stmt.setString(6, userid);
        stmt.executeUpdate();
    }
    
        
    
    public DbList<JsObject.User> getPhotoUsers(String id)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT userid, readonly from \"ObjectAccess\" "+
              " WHERE id=? AND photo = true",  
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, id);
        return new DbList<JsObject.User>( stmt.executeQuery(), rs ->
            { return new JsObject.User(rs.getString("userid"), rs.getBoolean("readonly"));  }
        );
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

