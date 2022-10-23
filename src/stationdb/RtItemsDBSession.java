/* 
 * Copyright (C) 2022 by Øyvind Hanssen (ohanssen@acm.org)
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
import  java.util.function.*;
import java.util.Set;

/**
 * Database transaction. 
 */
 
public class RtItemsDBSession extends DBSession
{
   
    private ServerAPI _api; 
    private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
    private int _exptime = 2 * 60;
    
    
    public static class Item {
        public String ident;
        public String cls;
        public String descr;
        public Reference pos;
        public boolean expired;
        public java.util.Date time;
        public byte[] obj;
        public String[] tags;
        
        public Item(String i, String c, Reference p, byte[] o, boolean exp, 
                    java.util.Date t, String ds, String[] tg)
            { ident=i; cls=c; pos=p; obj=o; expired=exp; time=t; descr=ds; tags=tg;}
    }
    
    
    public static class RsItem {
        public String ident;
        public boolean expired;
        public ResultSet rs;
        
        public byte[] getObj() {
            try {
                return rs.getBytes("obj");
            }
            catch (SQLException ex) {return null;}
        }
        
        public RsItem(ResultSet r) {
            try {
                rs= r;
                ident = rs.getString("ident");
                expired = rs.getBoolean("expired");
            }
            catch (SQLException ex) {}
        }
    }
    
    
   
    RtItemsDBSession (DataSource dsrc, ServerAPI api, boolean autocommit, Logfile log)
       throws DBSession.SessionError
    {
       super(dsrc, autocommit, log); 
       _api = api; 
       _exptime = _api.getIntProperty("aprs.expiretime", 60);
    }
   
   
   
    RtItemsDBSession(DBSession s) 
    {
       super(s);
       _api = s._api;
       int _exptime = _api.getIntProperty("aprs.expiretime", 60);
    }
   
   
          
       
    /**
     * Get geographical point from PostGIS. 
     * Convert it to jcoord LatLng reference. 
     */  
    private static Reference getRef(ResultSet rs, String field)
       throws java.sql.SQLException
    {
        PGgeometry geom = (PGgeometry) rs.getObject(field);
        if (geom == null)
            return null;
        org.postgis.Point pt = (org.postgis.Point) geom.getGeometry();
        return new LatLng(pt.y, pt.x);
    }
      

    
    public long getCount() 
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT count(ident) as cnt FROM \"RtPoint\" " );
        ResultSet rs = stmt.executeQuery();
        rs.next();
        return rs.getInt("cnt");
    }
    
    
    
    
    public Item getRtItem(String id) 
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT ident,cls,descr,time,pos,obj,(time+'"+_exptime+" minutes' < 'now') as expired " + 
                " FROM \"RtPoint\" where ident=?" );
        stmt.setString(1, id);      
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
            return new Item(
                rs.getString("ident"), rs.getString("cls"), getRef(rs, "pos"), 
                rs.getBytes("obj"), rs.getBoolean("expired"), rs.getDate("time"), rs.getString("descr"),
                null
            );
        return null;
    }
    
    
    
    
    public void addRtItem(Item it)  
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"RtPoint\" (ident, cls, descr, pos, obj, time, tags)" + 
                " VALUES (?, ?, ?, ?, ?, 'now', ?)" );
        it.ident.replaceAll("\u0000", "");
        stmt.setString(1, it.ident);
        stmt.setString(2, it.cls);
        it.descr.replaceAll("\u0000", "");
        stmt.setString(3, it.descr);
        setRef(stmt, 4, it.pos);
        stmt.setBytes(5, it.obj);
        stmt.setArray(6, getCon().createArrayOf("VARCHAR", it.tags));
        stmt.executeUpdate();
    }
    
    
    
    public void updateRtItem(Item it)  
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " UPDATE \"RtPoint\" SET cls=?, descr=?, pos=?, obj=?, time=?, tags=?" + 
                " WHERE ident=? " );
        stmt.setString(1, it.cls);
        it.descr.replaceAll("\u0000", "");
        stmt.setString(2, it.descr);
        setRef(stmt, 3, it.pos);
        stmt.setBytes(4, it.obj);
        stmt.setTimestamp(5, new java.sql.Timestamp(it.time.getTime()));  
        stmt.setArray(6, getCon().createArrayOf("VARCHAR", it.tags));
        stmt.setString(7, it.ident);  
        stmt.executeUpdate();
    }
       
       
       
    public void removeRtItem(String id)  
            throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " DELETE FROM \"RtPoint\" " + 
                " WHERE ident=? " );
        stmt.setString(1, id);
        stmt.executeUpdate();
    }
    
 
 
    
    public void searchGeo(Reference uleft, Reference lright, String[] tags, Consumer<RsItem> cons) 
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT ident, obj, (time+'"+_exptime+" minutes' < 'now') as expired " +
                " FROM \"RtPoint\" WHERE " +
                " (time+'"+_exptime+" minutes' >= 'now') AND "+
                " ST_Contains( " +
                "    ST_MakeEnvelope(?, ?, ?, ?, 4326), pos) "+
                ( tags!=null&&tags.length > 0 ? "AND tags && ?" : "") +
                " ORDER BY time DESC LIMIT 2000 ", 
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                
        stmt.setDouble( 1, uleft.toLatLng().getLng() );  // xmin
        stmt.setDouble( 2, lright.toLatLng().getLat() ); // ymin
        stmt.setDouble( 3, lright.toLatLng().getLng() ); // xmax
        stmt.setDouble( 4, uleft.toLatLng().getLat() );  // ymax
        if (tags!= null && tags.length > 0) 
            stmt.setArray ( 5, getCon().createArrayOf("VARCHAR", tags));
        ResultSet rs = stmt.executeQuery();
        
        while (rs.next()) 
            cons.accept(new RsItem(rs));    
    }
    
    
    
    
    
    public DbList<Item> searchMatch(String srch, boolean regex, String[] tags) 
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT ident,cls,descr,time,pos,obj,(time+'"+_exptime+" minutes' < 'now') as expired " +
                " FROM \"RtPoint\" WHERE " +
                " (time+'"+_exptime+" minutes' >= 'now') AND "+
                " ( ident "+ (regex ? "~" : "LIKE") + " ?  OR descr "+ (regex ? "~" : "LIKE") + " ?) " +
                ( tags!=null ? "AND tags @> ?" : "") +
                " ORDER BY ident ASC, time DESC ", 
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        stmt.setString( 1, srch );
        stmt.setString( 2, srch );
        stmt.setArray(3, getCon().createArrayOf("VARCHAR", tags));
        return new DbList(stmt.executeQuery(), rs -> 
            {
                return new Item(
                    rs.getString("ident"), rs.getString("cls"), getRef(rs, "pos"), 
                    rs.getBytes("obj"), rs.getBoolean("expired"), rs.getDate("time"), rs.getString("descr"), 
                    null
                );
            });    
    }
    
    
    
    public void putSysObj(String key, byte[] obj)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " UPDATE \"SysObject\" SET obj=?" + 
                " WHERE key=? " );  
        stmt.setBytes(1, obj);
        stmt.setString(2, key);
        stmt.executeUpdate();
        if (stmt.executeUpdate() > 0) 
            return;
            
        stmt = getCon().prepareStatement
              ( " INSERT INTO \"SysObject\" (key, obj) VALUES(?, ?) " );
        stmt.setString(1, key);
        stmt.setBytes(2, obj);
        stmt.executeUpdate();
    }
    
    
    
    public byte[] getSysObj(String key)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT obj FROM \"SysObject\" " + 
                " WHERE key=? " ); 
        stmt.setString(1, key);
        ResultSet rs = stmt.executeQuery();
        rs.next();
        return rs.getBytes("obj");
    }
    
    
}

