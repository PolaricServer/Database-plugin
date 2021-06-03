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
import  no.polaric.aprsd.*;
import  java.text.*;
import  java.sql.*;
import  javax.sql.*;
import  java.util.concurrent.locks.*; 
import  uk.me.jstott.jcoord.*;
import  no.polaric.aprsd.*;
import  org.postgis.PGgeometry;
import  java.io.*;



/**
 * Database transaction. 
 */
 
public class MyDBSession extends DBSession
{
   
   private ServerAPI _api; 
   private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
   
   MyDBSession (DataSource dsrc, ServerAPI api, boolean autocommit, Logfile log)
    throws DBSession.SessionError
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
     * Get points that were transmitted via a certain digipeater during a certain time span. 
     */
    public DbList<TPoint> getPointsVia(String digi, Reference uleft, Reference lright, java.util.Date from, java.util.Date to)
       throws java.sql.SQLException
    {
        _log.debug("MyDbSession", "getPointsVia: "+digi+", "+df.format(from)+" - "+df.format(to));
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT DISTINCT position "+ 
             " FROM \"AprsPacket\" p, \"PosReport\" r " +
             " WHERE  p.src=r.src " +
             " AND  p.time=r.rtime " + 
             " AND  (substring(p.path, '([^,\\*]+).*\\*.*')=? OR " +
                     " (substring(p.ipath, 'qAR,([^,\\*]+).*')=? AND p.path !~ '.*\\*.*')) " +
             (uleft==null ? "": " AND  position && ST_MakeEnvelope(?, ?, ?, ?, 4326) ") +
             
             " AND  p.time > ? AND p.time < ? LIMIT 15000",

             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, digi);
        stmt.setString(2, digi);

        if (uleft != null) {
            LatLng ul = uleft.toLatLng();
            LatLng lr = lright.toLatLng();
            stmt.setDouble(3, ul.getLng());
            stmt.setDouble(4, ul.getLat());
            stmt.setDouble(5, lr.getLng());
            stmt.setDouble(6, lr.getLat());
            stmt.setTimestamp(7, date2ts(from));
            stmt.setTimestamp(8, date2ts(to));
        }
        else {
            stmt.setTimestamp(3, date2ts(from));
            stmt.setTimestamp(4, date2ts(to));
        }
        stmt.setMaxRows(15000);
        
        return new DbList(stmt.executeQuery(), rs ->
            { return new TPoint(null, getRef(rs, "position")); });
    }
    
    
    
    public int addSign(long maxscale, String icon, String url, String descr, Reference pos, int cls, String uid)
            throws java.sql.SQLException
    {
         _log.debug("MyDbSession", "addSign: "+descr+", class="+cls);
         PreparedStatement stmt = getCon().prepareStatement
              ( "INSERT INTO \"Signs\" (maxscale, icon, url, description, position, class, userid)" + 
                "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id" );
         stmt.setLong(1, maxscale);
         stmt.setString(2, icon);
         stmt.setString(3, url);
         stmt.setString(4, descr);
         setRef(stmt, 5, pos);
         stmt.setInt(6, cls);
         stmt.setString(7, uid);
         ResultSet rs = stmt.executeQuery(); 
         rs.next();
         return rs.getInt("id");
    }
    public int addSign(long maxscale, String icon, String url, String descr, Reference pos, int cls)
        throws java.sql.SQLException
    { return addSign(maxscale, icon, url, descr, pos, cls, null); }
    
    
    
    public void updateSign(int id, long maxscale, String icon, String url, String descr, Reference pos, int cls, String uid)
            throws java.sql.SQLException
    {
        _log.debug("MyDbSession", "updateSign: "+id+", "+descr);
        PreparedStatement stmt = getCon().prepareStatement
            ( "UPDATE \"Signs\" SET maxscale=?, position=?, icon=?, url=?, description=?, class=?, userid=?"+
              "WHERE id=?" );
        stmt.setLong(1, maxscale);
        setRef(stmt, 2, pos);
        stmt.setString(3, icon);
        stmt.setString(4, url);
        stmt.setString(5, descr);
        stmt.setInt(6, cls);      
        stmt.setString(7, uid);
        stmt.setInt(8, id);
        stmt.executeUpdate();
    }  
    public void updateSign(int id, long maxscale, String icon, String url, String descr, Reference pos, int cls)
        throws java.sql.SQLException
    { updateSign(id, maxscale, icon, url, descr, pos, cls, null); }
    
    
    
    
    public Sign getSign(int id)
          throws java.sql.SQLException
    {
         _log.debug("MyDbSession", "getSign: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT s.id AS sid, position, maxscale, url, description, cl.name AS cname, "+
                " s.icon AS sicon, cl.icon AS cicon, class " +
                " FROM \"Signs\" s LEFT JOIN \"SignClass\" cl ON s.class=cl.id " +
                 "WHERE s.id=?" );
         stmt.setInt(1, id);
         ResultSet rs = stmt.executeQuery();
         if (rs.next()) {
            String icon = rs.getString("sicon");
            if (icon == null)
                icon = rs.getString("cicon");
                    
            // Item (Reference r, long sc, String ic, String url, String txt)
            return new Sign(rs.getInt("sid"), getRef(rs, "position"), rs.getLong("maxscale"), icon,
                    rs.getString("url"), rs.getString("description"), rs.getInt("class"), rs.getString("cname")   ); 
         }
         return null;
    }
    

    
    /**
     * Get list of signs in a specified geographic area and above a specified scale 
     */
    public DbList<Signs.Item> getSigns(long scale, Reference uleft, Reference lright)
       throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT s.id AS sid, position, maxscale, url, description, cl.name, s.icon AS sicon, cl.icon AS cicon " +
             " FROM \"Signs\" s LEFT JOIN \"SignClass\" cl ON s.class=cl.id" +
             " WHERE maxscale>=? AND position && ST_MakeEnvelope(?, ?, ?, ?, 4326) AND NOT s.hidden"+
             " LIMIT 300",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT );
        stmt.setLong(1, scale);
        LatLng ul = uleft.toLatLng();
        LatLng lr = lright.toLatLng();
        stmt.setDouble(2, ul.getLng());
        stmt.setDouble(3, ul.getLat());
        stmt.setDouble(4, lr.getLng());
        stmt.setDouble(5, lr.getLat());
        stmt.setMaxRows(300);
         
        return new DbList(stmt.executeQuery(), rs -> 
            {
                String icon = rs.getString("sicon");
                if (icon == null)
                    icon = rs.getString("cicon");

                // Item (Reference r, long sc, String ic, String url, String txt)
                return new Signs.Item(rs.getInt("sid"), getRef(rs, "position"), 0, icon,
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
         
        return new DbList(stmt.executeQuery(), rs -> 
            {
                String icon = rs.getString("sicon");
                if (icon == null)
                    icon = rs.getString("cicon");

                // Item (Reference r, long sc, String ic, String url, String txt)
                return new Sign(rs.getInt("sid"), getRef(rs, "position"), rs.getLong("maxscale"), icon,
                    rs.getString("url"), rs.getString("description"), rs.getInt("class"), rs.getString("cname")   );  
            });
    }
    
        
        
    public int deleteSign(int id)
          throws java.sql.SQLException
    {
         _log.debug("MyDbSession", "deleteSign: "+id);
         PreparedStatement stmt = getCon().prepareStatement
              ( "DELETE FROM \"Signs\"" + 
                "WHERE id=?" );
         stmt.setInt(1, id);
         return stmt.executeUpdate();
    }
    
    
    
    public DbList<Sign.Category> getCategories()
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"SignClass\" ORDER BY name ASC ", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
         
        return new DbList(stmt.executeQuery(), rs ->
            { return new Sign.Category(rs.getInt("id"), rs.getString("name"), rs.getString("icon")); });
    }
    
    
    
    
    /* FIXME: should true be default? */
    public DbList<TPoint> getTrail(String src, java.util.Date from, java.util.Date to)
            throws java.sql.SQLException
        { return getTrail(src,from,to,true); }
       
       
                                                                           
    /**
     * Get trail for a given station and a given time span. 
     */
    public DbList<TPoint> getTrail(String src, java.util.Date from, java.util.Date to, boolean rev)
       throws java.sql.SQLException
    {
        _log.debug("MyDbSession", "getTrail: "+src+ ", "+df.format(from)+" - "+df.format(to));
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT * FROM \"PosReport\"" +
             " WHERE src=? AND time >= ? AND time <= ?" + 
             " ORDER BY time "+(rev? "DESC" : "ASC")+" LIMIT 5000",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, src);
        stmt.setTimestamp(2, date2ts(from));
        stmt.setTimestamp(3, date2ts(to));
        stmt.setMaxRows(5000);
         
        return new DbList(stmt.executeQuery(), rs ->
            { return new TPoint(rs.getTimestamp("time"), getRef(rs, "position"));  });
    }
    

    
    
   /**
     * Get trail poiint for a given station and a given time. 
     */
    public Trail.Item getTrailPoint(String src, java.util.Date t)
       throws java.sql.SQLException
    { 
       _log.debug("MyDbSession", "getTrailPoint: "+src+", "+df.format(t));
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
    public AprsPoint getItem(String src, java.util.Date at)
       throws java.sql.SQLException
    {
        _log.debug("MyDbSession", "getItem:  "+src+", "+df.format(at));
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
     *  Return a list of the last n APRS packets from a given call.
     *
     * @param src from callsign
     * @param n   number of elements of list
     */
    public DbList<AprsPacket> getAprsPackets(String src, int n)
       throws java.sql.SQLException
    {    
        _log.debug("MyDbSession", "getAprsPackets:  "+src);
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT * FROM \"AprsPacket\"" +
             " WHERE src=?"  + 
             " ORDER BY time DESC LIMIT ?",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
       
        stmt.setString(1, src);
        stmt.setInt(2, n);
        
        return new DbList(stmt.executeQuery(), rs -> 
            {
                AprsPacket p =  new AprsPacket();
                String path = rs.getString("path");
                String ipath = rs.getString("ipath");
                
                p.source = _api.getChanManager().get( rs.getString("channel") );
                p.from = rs.getString("src");
                p.to = rs.getString("dest");
                p.via = (path==null ? "" : rs.getString("path") + ", ") + rs.getString("ipath");
                p.report = rs.getString("info");
                p.time = rs.getTimestamp("time");
                return p;
             });
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
    
    
    public void updateTracker(String id, String alias, String icon)
            throws java.sql.SQLException
    {
        _log.debug("MyDbSession", "updateTracker: "+id);
        PreparedStatement stmt = getCon().prepareStatement
            ( "UPDATE \"Tracker\" SET alias=?, icon=?"+
              "WHERE id=?" );
        stmt.setString(1, alias);
        stmt.setString(2, icon);
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
         return new DbList( stmt.executeQuery(), rs ->
            { return new Tracker(_api.getDB(), rs.getString("id"), user, rs.getString("alias"), rs.getString("icon"));  }
        );
    }
    
    
    public long addJsObject(String user, String tag, String data)  
            throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"JsObject\" (tag, data)" + 
                " VALUES (?, ?) RETURNING id" );
         stmt.setString(1, tag);
         stmt.setString(2, data);
         ResultSet rs = stmt.executeQuery(); 
         rs.next();
         long objid = rs.getLong("id");       
         
         /* Add user access to the object */
         PreparedStatement stmt2 = getCon().prepareStatement
              ( " INSERT INTO \"ObjectAccess\" (id, userid)" +
                " VALUES (?, ?)" );
         stmt2.setLong(1, objid);
         stmt2.setString(2, user);
         stmt2.executeUpdate();
         return objid;
    }
    
    
    
    public void updateJsObject(long ident, String data)  
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " UPDATE \"JsObject\"" + 
                " SET data=? WHERE id=?" );
        stmt.setString(1, data);
        stmt.setLong(2, ident);
        stmt.executeUpdate();
    }
    
    
    
    public int deleteJsObject(String user, String tag, long id)
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " DELETE FROM \"JsObject\" "+
              " WHERE tag=? AND id=?; ");
        stmt.setString(1, tag);
        stmt.setLong(2, id);
        return stmt.executeUpdate();
    }
    
    
    public DbList<JsObject> getJsObjects(String user, String tag)
          throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT id, data, readonly FROM \"JsObject\" NATURAL JOIN \"ObjectAccess\" " +
              " WHERE userid=? AND tag=? ORDER BY data ASC", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
         stmt.setString(1, user);
         stmt.setString(2, tag);
         return new DbList( stmt.executeQuery(), rs ->
            { return new JsObject(rs.getLong("id"), rs.getBoolean("readonly"), rs.getString("data"));  }
        );
    }
    
    
    public String getJsObject(String user, String tag, long id)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT data FROM \"JsObject\" NATURAL JOIN \"ObjectAccess\" " +
              " WHERE userid=? AND tag=? AND id=?", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, user);
        stmt.setString(2, tag);
        stmt.setLong(3, id);
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
            return rs.getString("data");
        return null;
    }
    
    
    public void shareJsObject(long ident, String owner, String userid, boolean readonly)
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"ObjectAccess\" (id, readonly, userid)" +
                " SELECT id, ?, ? "+
                " FROM \"JsObject\" NATURAL JOIN \"ObjectAccess\" "+
                "   WHERE id=? AND userid=? AND readonly=false AND NOT EXISTS "+
                "       (SELECT userid FROM \"ObjectAccess\" WHERE id=? AND userid=?) limit 1");
        stmt.setBoolean(1, readonly);
        stmt.setString(2, userid);
        stmt.setLong(3, ident);
        stmt.setString(4, owner);
        stmt.setLong(5, ident);
        stmt.setString(6, userid);
        stmt.executeUpdate();
    }
    
    
    public void shareJsObjects(String tag, String owner, String userid, boolean readonly)
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"ObjectAccess\" (id, readonly, userid)" +
                " SELECT id, readonly, ? "+
                " FROM \"JsObject\" NATURAL JOIN \"ObjectAccess\" "+
                "   WHERE tag=? AND userid=? AND readonly=false ");
        stmt.setString(1, userid);
        stmt.setString(2, tag);
        stmt.setString(3, owner);   
        stmt.executeUpdate();
    }
    
    
    public int unlinkJsObject(long ident, String owner, String userid)
            throws java.sql.SQLException
    {
        /* First, remove links from users */
        PreparedStatement stmt = getCon().prepareStatement
              ( " DELETE FROM \"ObjectAccess\" WHERE id=? AND userid=? "+
                " AND EXISTS " +
                "   ( SELECT id FROM \"JsObject\" NATURAL JOIN \"ObjectAccess\" " + 
                "     WHERE userid=? " + 
                (userid.equals(owner) ? ")" : " AND readonly=false) ")  
              );
        stmt.setLong(1, ident);
        stmt.setString(2, userid);
        stmt.setString(3, owner);
        stmt.executeUpdate();
        
        /* Now if no user-links left, remove JsObject */
        stmt = getCon().prepareStatement
              ( " DELETE FROM \"JsObject\" WHERE id=? AND NOT EXISTS "+
                "  ( SELECT id FROM \"ObjectAccess\" where id=? ) ");
        stmt.setLong(1, ident);
        stmt.setLong(2, ident);
        return stmt.executeUpdate();
    }
    
    
    public int unlinkJsObjects(String tag, String owner, String userid)
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " DELETE FROM \"ObjectAccess\" WHERE userid=? AND id IN" +
                " ( SELECT id FROM \"JsObject\" NATURAL JOIN \"ObjectAccess\" " +
                "   WHERE tag=? AND userid=? AND EXISTS " +
                "     ( SELECT id FROM \"JsObject\" NATURAL JOIN \"ObjectAccess\" " + 
                "       WHERE userid=? AND tag=? AND readonly=false) ) " );
                
        stmt.setString(1, userid);
        stmt.setString(2, tag);
        stmt.setString(3, userid);
        stmt.setString(4, owner);
        stmt.setString(5, tag);
        stmt.executeUpdate();
        
        /* Now if no user-links left, remove JsObject */
        stmt = getCon().prepareStatement
              ( " DELETE FROM \"JsObject\" WHERE id IN "+
                "  ( SELECT id FROM \"JsObject\" WHERE tag=? AND NOT EXISTS "+
                "    ( SELECT id FROM \"ObjectAccess\" WHERE id=\"JsObject\".id ) ) ");
        stmt.setString(1, tag);
        return stmt.executeUpdate();
    }
    
    
    public DbList<JsObject.User> getJsUsers(long id)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT userid, readonly from \"ObjectAccess\" "+
              " WHERE id=? ",  
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setLong(1, id);
        return new DbList( stmt.executeQuery(), rs ->
            { return new JsObject.User(rs.getString("userid"), rs.getBoolean("readonly"));  }
        );
    }
    
    
    
    public String getFileObject(long id)         
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"FileObject\" "  +
              " WHERE id=?", 
              ResultSet.CONCUR_READ_ONLY );
        stmt.setLong(1, id);
        ResultSet rs = stmt.executeQuery(); 
        if (rs.next())
            return rs.getString("data");
        return null;
    }
    
    
    public long addFileObject(InputStream data)        
        throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"FileObject\" (data)" + 
                " VALUES (?) RETURNING id" );
         stmt.setCharacterStream(1, new InputStreamReader(data));
         ResultSet rs = stmt.executeQuery(); 
         rs.next();
         return rs.getLong("id");
    }
    
    
    public int deleteFileObject(long id)   
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " DELETE FROM \"FileObject\" " +
              " WHERE id=?; ");
        stmt.setLong(1, id);
        return stmt.executeUpdate();
    }
    
    
}

