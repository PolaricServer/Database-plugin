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

package no.polaric.aprsdb.dbsync;
import  no.polaric.core.*;
import  no.polaric.aprsdb.*;
import  no.polaric.aprsd.*;
import  java.text.*;
import  java.sql.*;
import  javax.sql.*;
import  java.util.concurrent.locks.*; 
import  no.polaric.aprsd.*;
import  net.postgis.jdbc.PGgeometry;
import  java.io.*;



/**
 * Database transaction. 
 */
 
public class SyncDBSession extends DBSession
{
   
   private ServerConfig _api; 
   private DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd/HH:mm");
   
   
   public static class SyncOp {
        Date ts; 
        String cmd;
        SyncOp(Date t, String o) {
            ts=t; cmd=o;
        }
   }
   
   
   public static class SyncPeer  { 
        public String nodeid, url, items;
        public boolean active = false;
        
        public void setActive() {
            active = true;
        }
        
        public SyncPeer(String id, String u, String it) {
            nodeid=id; 
            url=u; 
            items=it;
        }
   }
   
   
   
   
   SyncDBSession (DataSource dsrc, ServerConfig api, boolean autocommit, Logfile log)
    throws DBSession.SessionError
   {
      super(dsrc, autocommit, log); 
      _api = api; 
   }
   
   
   SyncDBSession(DBSession s) 
     throws DBSession.SessionError
   {
       super(s);
   }
   
   

    /* 
     * Get time when cid/item was last updated 
     */
    public SyncOp getSync(String cid, String item)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT ts,op from \"DbSync\" "+
              " WHERE cid=? AND item=? " +
              " ORDER BY ts DESC ", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
              
        stmt.setString(1, cid);
        stmt.setString(2, item);
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
            return new SyncOp(new Date(rs.getTimestamp("ts").getTime()), rs.getString("op")); ;
        return null;
    }
    
    
    
    /* 
     * Set last-update-time for cid/item .
     */
    public void setSync(String cid, String item, String cmd, java.util.Date ts)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " UPDATE \"DbSync\"" + 
                " SET ts=?, op=? WHERE cid=? AND item=?" );
        stmt.setTimestamp(1, DBSession.date2ts(ts));
        stmt.setString(2, cmd);
        stmt.setString(3, cid);
        stmt.setString(4, item);
        int n = stmt.executeUpdate();
        
        if (n==0) {
            stmt = getCon().prepareStatement
              ( " INSERT INTO \"DbSync\" (cid,item,op,ts)" + 
                " VALUES (?, ?, ?, ?)" );
            stmt.setString(1, cid);
            stmt.setString(2, item);
            stmt.setString(3, cmd);
            stmt.setTimestamp(4, DBSession.date2ts(ts));
            stmt.executeUpdate();
        }
        
    }
    
    
    
    public void setStable(java.util.Date ts)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " UPDATE \"DbSync\"" + 
                " SET stable = 'true' WHERE ts=?" );
        stmt.setTimestamp(1, DBSession.date2ts(ts));       
        stmt.executeUpdate();
    }
    
        
    
    public void addSyncIncoming(String src, String origin,  java.util.Date ts)
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
        ( " INSERT INTO \"DbSyncIncoming\"(source, origin, ts)" + 
          " VALUES(?, ?, ?) " );
        stmt.setString(1, src);
        stmt.setString(2, origin);
        stmt.setTimestamp(3, DBSession.date2ts(ts));
        stmt.executeUpdate();
    }
    
    
    
    public String getSyncIncoming(String origin, java.util.Date ts) 
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT source FROM \"DbSyncIncoming\" "+ 
              " WHERE origin=? AND ts=? " );
        stmt.setString(1, origin);
        stmt.setTimestamp(2, DBSession.date2ts(ts));
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
            return rs.getString("source");
        return null;
    }
    
    
    
    public void removeSyncIncoming(String origin, java.util.Date ts) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
        ( " DELETE FROM \"DbSyncIncoming\"" + 
          " WHERE origin = ? AND ts <= ?" );
        stmt.setString(1, origin);
        stmt.setTimestamp(2, DBSession.date2ts(ts, 100));
        stmt.executeUpdate();
    }
    
    
    
    
    public DbList<Sync.Ack> getSyncAcksTo(String nodeid) 
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT origin,ts,conf from \"DbSyncAck\" "+ 
              " WHERE nodeid=? ORDER BY ts asc", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, nodeid);

        return new DbList<Sync.Ack>( stmt.executeQuery(), rs ->
            { return new Sync.Ack
               (rs.getString("origin"), rs.getTimestamp("ts").getTime(), rs.getBoolean("conf")); });      
    }
    
    
    
    
    public int removeSyncAcks(String nodeid, java.util.Date ts) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
        ( " DELETE FROM \"DbSyncAck\"" + 
          " WHERE nodeid = ? AND ts <= ?" );
        stmt.setString(1, nodeid);
        stmt.setTimestamp(2, DBSession.date2ts(ts, 100));
        int removed = stmt.executeUpdate();
        return 0;
    }
    
        
        
    
    public void addSyncAck(String nodeid, String origin, java.util.Date ts, boolean conf) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
        ( " INSERT INTO \"DbSyncAck\" (origin, ts, nodeid, conf)" + 
          " VALUES(?, ?, ?, ?) " );
        stmt.setString(1, origin);
        stmt.setTimestamp(2, DBSession.date2ts(ts));
        stmt.setString(3, nodeid);
        stmt.setBoolean(4, conf);
        stmt.executeUpdate();
    }
    
    
    
    
    
    /*
     * Return true if a sync message is found
     */
    public boolean hasSyncUpdate(String origin, java.util.Date ts) 
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"DbSyncMessage\" "+
              " WHERE origin=? AND ts=?" );
        stmt.setString(1, origin);
        stmt.setTimestamp(2, DBSession.date2ts(ts));
        
        ResultSet rs = stmt.executeQuery();
        return rs.next();
    }
    
    
    
    
    /*
     * Get sync update messages to a given node, that are not sent yet
     */
    public DbList<Sync.ItemUpdate> getSyncUpdatesTo(String nodeid) 
        throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( " SELECT * from \"DbSyncMessage\" NATURAL JOIN \"DbSyncMessageTo\" "+ 
              " WHERE nodeid=? and sent='false' ORDER BY ts asc", 
              ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY );
        stmt.setString(1, nodeid);

        return new DbList<Sync.ItemUpdate>( stmt.executeQuery(), rs ->
            { return new Sync.ItemUpdate
               (rs.getString("cid"), rs.getString("item"), rs.getString("userid"), rs.getString("cmd"), rs.getString("arg"), 
               rs.getTimestamp("ts").getTime(), rs.getString("origin"));  });      
    }
    

    
    
    /*
     * Remove sync update messages from a given node before a given time
     */
    public int removeSyncUpdatesFrom(String origin, java.util.Date ts) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " DELETE FROM \"DbSyncMessageTo\"" + 
                " WHERE origin = ? AND ts <= " + (ts==null ? "'now'" :"?") );
        stmt.setString(1, origin);
        if (ts != null)
            stmt.setTimestamp(2, DBSession.date2ts(ts, 100));
        int removed = stmt.executeUpdate();
        
        /* Cleanup */
        PreparedStatement stmt2 = getCon().prepareStatement
              ( " DELETE FROM \"DbSyncMessage\" m WHERE NOT EXISTS " +
                " (SELECT ts FROM \"DbSyncMessageTo\" t WHERE m.ts = t.ts AND m.origin = t.origin) " );
        removed += (100 * stmt2.executeUpdate());
        return removed; 
    }
    
    
    
    /*
     * Mark sync update messages as sent and delivered to peer
     */
    public void setSentSyncUpdates(String nodeid, java.util.Date ts) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
            ( "UPDATE \"DbSyncMessageTo\" SET sent='true' WHERE nodeid=? AND ts<=? " );
        stmt.setString(1, nodeid);
        stmt.setTimestamp(2, DBSession.date2ts(ts, 100));
        stmt.executeUpdate();
    }
    
    
    
    /*
     *  Add a sync update message. 
     */
    public void addSyncUpdate(Sync.ItemUpdate upd) 
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"DbSyncMessage\" (origin,ts,cid,item,userid,cmd,arg) " + 
                " VALUES (?,?,?,?,?,?,?) " );
        stmt.setString(1, upd.origin );
        stmt.setTimestamp(2, new Timestamp(upd.ts));
        stmt.setString(3, upd.cid);
        stmt.setString(4, upd.itemid);
        stmt.setString(5, upd.userid);
        stmt.setString(6, upd.cmd);
        stmt.setString(7, upd.arg);
        stmt.executeUpdate();
    }
    
    
    /*
     * Add destination nodes for a given sync update message. 
     */
    public void addSyncUpdatePeer(String nodeid, Sync.ItemUpdate upd)
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"DbSyncMessageTo\" (origin,ts,nodeid) " + 
                " VALUES (?,?,?)" );
        stmt.setString(1,upd.origin);
        stmt.setTimestamp(2, new Timestamp(upd.ts));
        stmt.setString(3, nodeid);
        stmt.executeUpdate();
    }
    
    

    public void addSyncPeer(String nodeid, String items, String url)
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " INSERT INTO \"DbSyncPeers\" (nodeid,item,url) " + 
                " VALUES (?,?,?)" );
        stmt.setString(1,nodeid );
        stmt.setString(2,items);
        stmt.setString(3,url);          
        stmt.executeUpdate();
    }
    
    
    
    public void removeSyncPeer(String nodeid)
                throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " DELETE FROM \"DbSyncPeers\"" + 
                " WHERE nodeid=? " );
        stmt.setString(1, nodeid);
        stmt.executeUpdate();
    }
    
    
    
    public SyncPeer getSyncPeer(String nodeid) 
                    throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT * FROM \"DbSyncPeers\"" + 
                " WHERE nodeid=? " );
        stmt.setString(1, nodeid);
        ResultSet rs = stmt.executeQuery();
        if (rs.next())
            return new SyncPeer(nodeid, rs.getString("url"), rs.getString("item")); ;
        return null;
    }
        
        
    
    public DbList<SyncPeer> getSyncPeers(boolean server) 
                    throws java.sql.SQLException
    {
        PreparedStatement stmt = getCon().prepareStatement
              ( " SELECT * FROM \"DbSyncPeers\""+ 
                (server ? " WHERE NOT url is null " : ""),
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY  );
        return new DbList<SyncPeer>( stmt.executeQuery(), rs ->
            { return new SyncPeer
               (rs.getString("nodeid"), rs.getString("url"), rs.getString("item"));  }); 
    }
    
}

