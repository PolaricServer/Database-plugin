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
 
public class MyDBSession extends DBSession
{
    private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
       
    
    public static class TrailItem {
        public String ident;
        public String channel;       
        public LatLng pos;
        public java.util.Date time;
        public char symbol, symtab;
        public String path, ipath;
    
    
        /* Should be used inside a transaction */
        public TrackerPoint toPoint() {
            String name[] = ident.split("@", 2);
            AprsPoint x = null;
            if (name.length > 1) {
                Station owner = null; // FIXME 
                x = new AprsObject(owner, name[0]);
            } else {
                x = new Station(ident);
                if (path != null)
                    ((Station)x).setPathInfo(path); // FIXME. Move pathinfo to superclass? 
            }
            x.updatePosition(time, pos); 
            x.setSymbol(symbol);
            x.setSymtab(symtab);
            x.setNoDb(true);
            return x;
        }     

             
        public TrailItem(String i, String c, LatLng p, java.util.Date t, char sym, char stab, String pt, String ipt)
            { ident=i; channel=c; pos=p; time=t; symbol=sym; symtab=stab; path=pt; ipath=ipt; }
    }
    
   
   
    /* Should be used inside a transaction */
    public void getAnnotations(TrackerPoint tp, java.util.Date time)       
        throws java.sql.SQLException
    {
        String alias = getAliasAt(tp.getIdent(), time);
        String icon = getIconAt(tp.getIdent(), time);
        if (alias != null)
            tp.setAlias(alias);
        if (icon != null)
            tp.setIcon(icon);
    }
    
   
   
   
    MyDBSession (DataSource dsrc, ServerAPI api, boolean autocommit, Logfile log)
      throws DBSession.SessionError
    {
       super(dsrc, autocommit, log); 
       _api = api; 
    }
   
   
   
       
    /**
     * Get geographical point from PostGIS. 
     * Convert it to LatLng reference. 
     */  
    private LatLng getRef(ResultSet rs, String field)
       throws java.sql.SQLException
    {
        PGgeometry geom = (PGgeometry) rs.getObject(field);
        Point pt = (Point) geom.getGeometry();
        return new LatLng(pt.y, pt.x);
    }
      

  
  
  
    /**
     * Get points that were transmitted via a certain digipeater during a certain time span. 
     */
    public DbList<TPoint> getPointsVia(String digi, LatLng uleft, LatLng lright, java.util.Date from, java.util.Date to)
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
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY 
        );
        stmt.setString(1, digi);
        stmt.setString(2, digi);

        if (uleft != null) {
            LatLng ul = uleft;
            LatLng lr = lright;
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
        
        return new DbList<TPoint>(stmt.executeQuery(), rs ->
            { return new TPoint(null, getRef(rs, "position"), null); });
    }
    
    


    private String getPath(ResultSet rs) 
        throws java.sql.SQLException 
    {
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
        return p;
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
           ( " SELECT pr.time, position, path, ipath, nopkt FROM \"PosReport\" AS pr" +
             " LEFT JOIN \"AprsPacket\" AS ap ON pr.src = ap.src AND pr.rtime = ap.time " +
             " WHERE pr.src=? AND pr.time >= ? AND pr.time <= ?" + 
             " ORDER BY pr.time "+(rev? "DESC" : "ASC")+" LIMIT 5000",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, src);
        stmt.setTimestamp(2, date2ts(from));
        stmt.setTimestamp(3, date2ts(to));
        stmt.setMaxRows(5000);
                
        return new DbList<TPoint>(stmt.executeQuery(), rs -> { 
            return new TPoint(rs.getTimestamp("time"), getRef(rs, "position"), getPath(rs));  
        });
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
          return new Trail.Item(rs.getTimestamp("time"), getRef(rs, "position"), 
                         rs.getInt("speed"), rs.getInt("course"), getPath(rs) );  
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
        if (at==null)
            at = new java.util.Date();
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
        
        x.setNoDb(true);
        if (rs.next()) {
            x.updatePosition(rs.getDate("time"), getRef(rs, "position")); 
            x.setSymbol(rs.getString("symbol").charAt(0) );
            x.setSymtab(rs.getString("symtab").charAt(0) );
        }
        return x; 
    } 

    
    
    /**
     * Get activity within a geographical area in a given timespan.  
     */
    public DbList<TrailItem> getTrailsAt(LatLng uleft, LatLng lright, java.util.Date tto)
       throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT pr.src as ident, pr.channel, pr.time, position, symbol, symtab, path, ipath, nopkt " +
             " FROM \"PosReport\" AS pr" +
             " LEFT JOIN \"AprsPacket\" AS ap ON pr.src = ap.src AND pr.rtime = ap.time " +
             " WHERE ST_Contains( " +
             "    ST_MakeEnvelope(?, ?, ?, ?, 4326), position) "+
             " AND pr.time <= ? AND pr.time + INTERVAL '2 hour' > ? " + 
             " ORDER BY pr.src, pr.time DESC",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
             
        stmt.setDouble( 1, uleft.getLng() );  // xmin
        stmt.setDouble( 2, lright.getLat() ); // ymin
        stmt.setDouble( 3, lright.getLng() ); // xmax
        stmt.setDouble( 4, uleft.getLat() );  // ymax
        stmt.setTimestamp(5, date2ts(tto));
        stmt.setTimestamp(6, date2ts(tto));
        
        return new DbList<TrailItem>(stmt.executeQuery(), rs -> 
            {
                return new TrailItem (
                    rs.getString("ident"), rs.getString("channel"), getRef(rs, "position"), 
                    rs.getTimestamp("time"), rs.getString("symbol").charAt(0), rs.getString("symtab").charAt(0),  
                    rs.getString("path"), rs.getString("ipath")
                );
            });  
    }
    
    
    
    public String getAliasAt(String ident, java.util.Date time)      
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT alias from \"Annotation\" "+
              " WHERE alias IS NOT NULL AND src=? AND tstart < ? AND (tend IS NULL OR tend > ?) ",
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, ident);
        stmt.setTimestamp(2, date2ts(time));
        stmt.setTimestamp(3, date2ts(time));
        
        ResultSet rs = stmt.executeQuery();
        if (rs.first())
            return rs.getString("alias");
        return null;
    }
       
    
    
    public String getIconAt(String ident, java.util.Date time)      
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT icon from \"Annotation\" "+
              " WHERE icon IS NOT NULL AND src=? AND tstart < ? AND (tend IS NULL OR tend > ?) ",
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, ident);
        stmt.setTimestamp(2, date2ts(time));
        stmt.setTimestamp(3, date2ts(time));
        
        ResultSet rs = stmt.executeQuery();
        if (rs.first())
            return rs.getString("icon");
        return null;
    }
    

            
    public DbList<String> getTagsAt(String ident, java.util.Date time)      
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT tag from \"Annotation\" "+
              " WHERE tag IS NOT NULL AND src=? AND tstart < ? AND (tend IS NULL OR tend > ?) ",
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, ident);
        stmt.setTimestamp(2, date2ts(time));
        stmt.setTimestamp(3, date2ts(time));
        
        return new DbList<String>( stmt.executeQuery(), rs -> {
            return rs.getString("tag");
        });
    }
    
    
    
    /** 
     *  Return a list of the last n APRS packets from a given call.
     *
     * @param src from callsign
     * @param n   number of elements of list
     * @param at  time (search up to this time). If null, ignore
     * @param tfrom  time (search from this time). If null, ignore
     */
    public DbList<AprsPacket> getAprsPackets(String src, int n, java.util.Date at,  java.util.Date tfrom)
       throws java.sql.SQLException
    {    
        _log.debug("MyDbSession", "getAprsPackets:  "+src);
        PreparedStatement stmt = getCon().prepareStatement
           ( " SELECT * FROM \"AprsPacket\"" +
             " WHERE src=?"  + 
             (at != null ? " AND time <= ?" : "") +
             (tfrom != null ? " AND time >= ?" : "") +
             " ORDER BY time DESC LIMIT ?",
             ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
       
        int i = 1;
        stmt.setString(i++, src);
        if (at != null) 
            stmt.setTimestamp(i++, new Timestamp(at.getTime()));
        if (tfrom != null) 
            stmt.setTimestamp(i++, new Timestamp(tfrom.getTime()));
        stmt.setInt(i, n);
        
        
        return new DbList<AprsPacket>(stmt.executeQuery(), rs -> 
            {
                AprsPacket p =  new AprsPacket();
                String path = rs.getString("path");
                String ipath = rs.getString("ipath");
                
                p.source = _api.getChanManager().get( rs.getString("channel") );
                p.from = rs.getString("src");
                p.to = rs.getString("dest");
                
                p.via = (path==null ? "" : path); 
                if (ipath != null && !ipath.equals(""))
                    p.via += (!p.via.equals("") ? "," : "") + ipath;
                
                p.report = rs.getString("info");
                p.time = rs.getTimestamp("time");
                return p;
             });
    }
 
    public DbList<AprsPacket> getAprsPackets(String src, int n)
       throws java.sql.SQLException
        { return getAprsPackets(src,n,null,null); } 
 
 

    public String addJsObject(String srvid, String user, String tag, String data)  
            throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"JsObject\" (id, tag, data)" + 
                " VALUES ( nextval('jsobject_seq') || '@" + srvid + "', ?, ?) RETURNING id" );
         stmt.setString(1, tag);
         stmt.setString(2, data);
         ResultSet rs = stmt.executeQuery(); 
         rs.next();
         String objid = rs.getString("id");       
         
         /* Add user access to the object */
         PreparedStatement stmt2 = getCon().prepareStatement
              ( " INSERT INTO \"ObjectAccess\" (id, userid)" +
                " VALUES (?, ?)" );
         stmt2.setString(1, objid);
         stmt2.setString(2, user);
         stmt2.executeUpdate();
         return objid;
    }
    
    
    
    public void addJsObjectIdent(String id, String user, String tag, String data)  
            throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"JsObject\" (id, tag, data)" + 
                " VALUES (?, ?, ?) " );
         stmt.setString(1, id);
         stmt.setString(2, tag);
         stmt.setString(3, data);
         stmt.executeUpdate(); 
       
         /* Add user access to the object */
         PreparedStatement stmt2 = getCon().prepareStatement
              ( " INSERT INTO \"ObjectAccess\" (id, userid)" +
                " VALUES (?, ?)" );
         stmt2.setString(1, id);
         stmt2.setString(2, user);
         stmt2.executeUpdate();
    }
    
    
    
    public void updateJsObject(String ident, String data)  
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " UPDATE \"JsObject\"" + 
                " SET data=? WHERE id=?" );
        stmt.setString(1, data);
        stmt.setString(2, ident);
        stmt.executeUpdate();
    }
    
    
    
    public int deleteJsObject(String user, String tag, String id)
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " DELETE FROM \"JsObject\" "+
              " WHERE tag=? AND id=?; ");
        stmt.setString(1, tag);
        stmt.setString(2, id);
        return stmt.executeUpdate();
    }
    
    
    public DbList<JsObject> getJsObjects(String user, String group, String tag)
          throws java.sql.SQLException
    {
         PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT DISTINCT ON (id) id, userid, data, readonly FROM \"JsObject\" NATURAL JOIN \"ObjectAccess\" " +
              " WHERE (userid=? OR userid='#ALL' OR userid=?) AND tag=? ORDER BY id, readonly ASC, userid DESC, data ASC", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
         stmt.setString(1, (user==null ? "_NO-USER_" : user));
         stmt.setString(2, "@"+(group==null ? "NOLOGIN": group));
         stmt.setString(3, tag);
         
         return new DbList<JsObject>( stmt.executeQuery(), rs -> {
                boolean ro = rs.getBoolean("readonly");
                if (rs.getString("userid").matches("(#ALL)|(@.*)"))
                    ro = true;
                boolean nr = rs.getString("userid").matches("(#ALL)|(@.*)");
                
                return new JsObject(rs.getString("id"), ro, nr, rs.getString("data"));  
            }
        );
    }
    
    
    
    
    public boolean haveJsObject(String id)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT data FROM \"JsObject\" WHERE id=? ",  ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, id);
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
            return true;
        return false;
    }
            
            
            
            
    public String getJsObject(String user, String group, String tag, String id)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT data FROM \"JsObject\" NATURAL JOIN \"ObjectAccess\" " +
              " WHERE (userid=? OR userid='#ALL' OR userid=?) AND tag=? AND id=?", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, (user==null ? "NO-USER" : user));
        stmt.setString(2, "@"+(group==null ? "NOLOGIN": group));
        stmt.setString(3, tag);
        stmt.setString(4, id);
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
            return rs.getString("data");
        return null;
    }
    
    
    /* Share a specific object */
    public void shareJsObject(String ident, String owner, String userid, boolean readonly)
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
        stmt.setString(3, ident);
        stmt.setString(4, owner);
        stmt.setString(5, ident);
        stmt.setString(6, userid);
        stmt.executeUpdate();
    }
    
    
    /* Share all objects that have a specific userid and tag */
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
    

                
    public int unlinkJsObject(String ident, String owner, String userid)
            throws java.sql.SQLException
    {
        /* First, remove links from users where owner has a non-readonly link */
        PreparedStatement stmt = getCon().prepareStatement
              ( " DELETE FROM \"ObjectAccess\" WHERE id=? "+
                " AND userid=? "+
                " AND EXISTS " +
                "   ( SELECT id FROM \"ObjectAccess\" " + 
                "     WHERE id=? AND userid=? AND readonly=false ) " 
              );
        stmt.setString(1, ident);
        stmt.setString(2, userid);
        stmt.setString(3, ident);
        stmt.setString(4, owner);
        stmt.executeUpdate();
        
        /* Remove dangling @group and #ALL links */
        stmt = getCon().prepareStatement
              ( " DELETE FROM \"ObjectAccess\" WHERE id=? AND userid ~ '[@#].+' "+
                " AND NOT EXISTS "+
                "    ( SELECT id FROM \"ObjectAccess\" WHERE id=? AND NOT userid ~ '[@#].+') " );
        stmt.setString(1,ident);
        stmt.setString(2, ident);
        stmt.executeUpdate();
        
        /* Now if no user-links left, remove JsObject */
        stmt = getCon().prepareStatement
              ( " DELETE FROM \"JsObject\" WHERE id=? AND NOT EXISTS "+
                "  ( SELECT id FROM \"ObjectAccess\" where id=? ) ");
        stmt.setString(1, ident);
        stmt.setString(2, ident);
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
        
        
        /* Remove dangling @group and #ALL links */
        stmt = getCon().prepareStatement
              ( " DELETE FROM \"ObjectAccess\" WHERE userid ~ '[@#].+' AND id IN "+
                "   ( SELECT id FROM \"JsObject\" xx NATURAL JOIN \"ObjectAccess\" " +
                "     WHERE tag=? AND userid ~ '[@#].+' "+
                "     AND NOT EXISTS "+
                "      ( SELECT id FROM \"ObjectAccess\" "+
                "        WHERE id=xx.id AND NOT userid ~ '[@#].+') " );
        stmt.setString(1, tag);
        stmt.setString(2, tag);
        stmt.executeUpdate();
        
        
        /* Now if no user-links left, remove JsObjects */
        stmt = getCon().prepareStatement
              ( " DELETE FROM \"JsObject\" WHERE id IN "+
                "  ( SELECT id FROM \"JsObject\" WHERE tag=? AND NOT EXISTS "+
                "    ( SELECT id FROM \"ObjectAccess\" WHERE id=\"JsObject\".id ) ) ");
        stmt.setString(1, tag);
        return stmt.executeUpdate();
    }
    
    
    
    public DbList<JsObject.User> getJsUsers(String id)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT userid, readonly from \"ObjectAccess\" "+
              " WHERE id=? ",  
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, id);
        return new DbList<JsObject.User>( stmt.executeQuery(), rs ->
            { return new JsObject.User(rs.getString("userid"), rs.getBoolean("readonly"));  }
        );
    }

    
    
    
    
    public void setAlias(String ident, String alias) 
         throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"Annotation\" "+
              " WHERE src=? AND alias IS NOT NULL AND tend IS NULL ORDER BY tstart DESC",  
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, ident);
        ResultSet rs = stmt.executeQuery();
        Timestamp tstart = null;
        if (rs.first())
            tstart = rs.getTimestamp("tstart");
            
        if (tstart != null) {
            stmt = getCon().prepareStatement
              ( " UPDATE \"Annotation\" SET tend = 'now' "+
                " WHERE src=? AND tstart = ? "); 
            stmt.setString(1, ident);
            stmt.setTimestamp(2, tstart);
            stmt.executeUpdate();
        }
            
        if (alias != null) {
            stmt = getCon().prepareStatement
              ( " INSERT INTO \"Annotation\" (src, alias, tstart, tend) "+
                " VALUES (?, ?, 'now', NULL) "); 
            stmt.setString(1, ident);
            stmt.setString(2, alias);
            stmt.executeUpdate();
        }    
    }
        
        
    public void setIcon(String ident, String icon) 
         throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"Annotation\" "+
              " WHERE src=? AND icon IS NOT NULL AND tend IS NULL ORDER BY tstart DESC",  
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, ident);
        ResultSet rs = stmt.executeQuery();
        Timestamp tstart = null;
        if (rs.first())
            tstart = rs.getTimestamp("tstart");
            
        if (tstart != null) {
            stmt = getCon().prepareStatement
              ( " UPDATE \"Annotation\" SET tend = 'now' "+
                " WHERE src=? AND tstart = ? "); 
            stmt.setString(1, ident);
            stmt.setTimestamp(2, tstart);
            stmt.executeUpdate();
        }
            
        if (icon != null) {
            stmt = getCon().prepareStatement
              ( " INSERT INTO \"Annotation\" (src, icon, tstart, tend) "+
                " VALUES (?, ?, 'now', NULL) "); 
            stmt.setString(1, ident);
            stmt.setString(2, icon);
            stmt.executeUpdate();
        }    
    }
    
    
    
    public void setTag(String ident, String tag, boolean delete) 
         throws java.sql.SQLException
    {
        if (tag == null)
            return;
            
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"Annotation\" "+
              " WHERE src=? AND tag=? AND tend IS NULL ORDER BY tstart DESC",  
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, ident);
        stmt.setString(2, tag);
        ResultSet rs = stmt.executeQuery();
        Timestamp tstart = null;
        if (rs.first())
            tstart = rs.getTimestamp("tstart");
            
        if (delete) {
            stmt = getCon().prepareStatement
              ( " UPDATE \"Annotation\" SET tend = 'now' "+
                " WHERE src=? AND tstart = ? "); 
            stmt.setString(1, ident);
            stmt.setTimestamp(2, tstart);
            stmt.executeUpdate();
        }
        else if (tstart == null) {
            stmt = getCon().prepareStatement
              ( " INSERT INTO \"Annotation\" (src, tag, tstart, tend) "+
                " VALUES (?, ?, 'now', NULL) "); 
            stmt.setString(1, ident);
            stmt.setString(2, tag);
            stmt.executeUpdate();
        }    
    }
        

}

