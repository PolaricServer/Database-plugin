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
import java.util.Hashtable;
import java.util.Enumeration;
import java.sql.*;
import java.util.*;
import java.io.*;


/**
 * This is the database schema installer
 */
public class DbInstaller 
{    
     private static String _url, _login, _passwd;
     private static Connection _db;
     
     /* Schema version - when changing schema with a release, inccrease and provide upgrade method */
     private static final int _VERSION = 13; 
     
         
     static {
        // Load the JDBC driver
        try {
          Class.forName("org.postgresql.Driver");
        }
        catch (ClassNotFoundException e) {
            System.err.println("Board(static init): Couldn't load JDBC driver");
            System.exit(1);
        }
     }
     
     
     protected static void createClass(String name, String superclass, String attrs)
     {
        updateQuery(
           "CREATE TABLE \""+ name + "\" (" +attrs + ")" + 
           (superclass!=null ? (" INHERITS (\""+superclass+"\")") : ""));
        System.out.println("Created table "+name);   
     }
     
     
     protected static void updateQuery(String query, boolean noMsg)
     {
         try {
            Statement stmt = _db.createStatement();
            stmt.executeUpdate ( query + ";");
         }
         catch (SQLException e)
         {  
            if (!noMsg)
                System.out.println(e.getMessage());
         }
     }     
     
     protected static void updateQuery(String query)
        { updateQuery(query, false); }
     
     
     
     protected static void removeClass(String name)
     {
         try {
            Statement stmt = _db.createStatement();
            stmt.executeUpdate
               ( "DROP TABLE \""+ name + "\"");
            System.out.println("Removed table "+name);
         }
         catch (SQLException e)
         { }
     }
     
     
     
     protected static void addGeoField(String cls, String fname, int srid, String type, int dim)
     {
         updateQuery("SELECT AddGeometryColumn('"+cls+"', '"+fname+"', "+srid+", '"+type+"', "+dim+")", true);
     }
     
     
     
     protected static void remove(Properties p)
     {
         removeClass("MetaData");
         removeClass("AprsPacket"); 
         removeClass("PosReport");     
         removeClass("StatusReport"); 
         removeClass("Annotation");
         removeClass("AprsMessage");   
         removeClass("Mission");
         removeClass("Tracker");
         removeClass("JsObject");
         removeClass("ObjectAccess");
         removeClass("SignClass");
         removeClass("Signs"); 
         removeClass("TrTags");
         removeClass("DbSync");
         removeClass("DbSyncQueue");
         removeClass("ServerStats");
         removeClass("ServerStart");
     }
     
     
      
     protected static void installRelations(Connection db) {
    
        createClass("MetaData", null, 
                        "version integer not null" );
                        
        updateQuery("INSERT INTO \"MetaData\" (version) values("+_VERSION+");");
        
                   
        createClass("AprsPacket", null, 
                        "channel varchar(25) not null, " +
                        "src varchar(10) not null, " +
                        "dest varchar(10) not null, " +
                        "time timestamp without time zone, " +
                        "path text, " +
                        "ipath text, " +
                        "info text " );
                                                                     
                                                            
        createClass("AprsMessage", null, 
                        "channel varchar(25) not null, " +
                        "src varchar(20) not null, " +
                        "time timestamp without time zone, " +
                        "rtime timestamp without time zone, " +
                        "PRIMARY KEY (time, src) " );
                   // Note that we use timestamp from tracker if available. 
                   // We added received time to be able to join with AprsPacket. 
                   // Normal form? 
                   
                               
        createClass("PosReport", "AprsMessage",
                        "speed  integer, " +
                        "course integer, " +
                        "symbol character default '.'," +
                        "symtab character default '/'," + 
                        "comment text,"+
                        "nopkt  boolean");
        addGeoField("PosReport", "position", 4326, "POINT", 2); /* WGS84 Coordinate system */
            
                               
        createClass("StatusReport", "AprsMessage",
                        "dest    varchar(10) not null, " +
                        "info    text ");           
        

        createClass("Annotation", null, 
                        "src     varchar(20) not null, " +
                        "alias   varchar(30) default null, " +
                        "icon    varchar default null, " +
                        "tag     varchar default null, " +
                        "tstart  timestamp without time zone not null, "  +
                        "tend    timestamp without time zone " );      
            
                
        createClass("Tracker", null, 
                        "id      varchar(20) not null PRIMARY KEY, " +
                        "userid  varchar(20), " +
                        "alias   varchar(30), " +
                        "icon    varchar " );

        createClass("SignClass", null, 
                        "id      SERIAL PRIMARY KEY, "+
                        "name    text, "+
                        "icon    text ");
                               
        createClass("Signs", null,
                        "id          varchar, "+
                        "class       integer REFERENCES \"SignClass\" (id) ON DELETE SET NULL, "+
                        "maxscale    integer, "+
                        "icon        text," +
                        "url         text," +
                        "description text," +
                        "\"group\"   text," +
                        "userid      text," +
                        "picture     boolean default false," +
                        "approved    boolean default false, " +
                        "hidden      boolean default false ");
        addGeoField("Signs", "position", 4326, "POINT", 2); /* WGS84 Coordinate system */           
                        
                        
        createClass("Photo", null, 
                        "id          varchar not null, "+
                        "userid      varchar(20) not null," +
                        "time        timestamp without time zone not null, "+
                        "descr       text,"+
                        "image       bytea ");
        addGeoField("Photo", "position", 4326, "POINT", 2); /* WGS84 Coordinate system */
        
        
        createClass("JsObject", null, 
                        "id      varchar not null PRIMARY KEY, " +
            //            "parent  varchar REFERENCES \"JsObject\" (id) ON DELETE CASCADE   // ADD when ready
                        "tag     varchar(40), " + 
                        "data    text" ); // JSON in most cases

        createClass("ObjectAccess", null, 
                        "id       varchar REFERENCES \"JsObject\" (id) ON DELETE CASCADE, " +
                        "readonly boolean DEFAULT 'false', " + 
                        "photo    boolean DEFAULT 'false', " +
                        "userid   varchar(20) " );         
                               
        /* New in schema v. 4 */                
        createClass("TrTags", null, 
                        "userid  varchar(20) not null, " +
                        "tag     varchar(40) not null" );
                               
         
        createClass("DbSync", null, 
                        "cid    varchar NOT NULL, " +
                        "item   varchar NOT NULL, " +
                        "ts     timestamp without time zone NOT NULL, " +
                        "op     varchar(10) not null, " +
                        "stable boolean not null default false, "+
                        "PRIMARY KEY (cid,item) " );
         
         
         createClass("DbSyncMessage", null, 
                        "origin varchar NOT NULL, " +
                        "ts     timestamp without time zone NOT NULL, " +
                        "cid    varchar NOT NULL, " +
                        "item   varchar NOT NULL, " +
                        "userid varchar, " +
                        "cmd    varchar, " +
                        "arg    text, " +
                        "PRIMARY KEY (origin, ts) " );
   
         
         createClass("DbSyncMessageTo", null,
                        "origin varchar NOT NULL, " + 
                        "ts     timestamp without time zone NOT NULL, " + 
                        "nodeid varchar NOT NULL, " +
                        "sent   boolean default false, " +
                        "PRIMARY KEY (origin,ts, nodeid), " +
                        "FOREIGN KEY (origin, ts) REFERENCES \"DbSyncMessage\" (origin, ts) ON DELETE CASCADE " );
         
         
         createClass("DbSyncIncoming", null, 
                        "source varchar NOT NULL, " +
                        "origin varchar NOT NULL, " +
                        "ts timestamp without time zone NOT NULL, " +
                        "PRIMARY KEY (origin,ts) " );
                        
         
         createClass("DbSyncAck", null, 
                        "origin varchar NOT NULL, " +
                        "ts     timestamp without time zone NOT NULL, " +
                        "nodeid varchar NOT NULL, " +
                        "conf   boolean, " +
                        "PRIMARY KEY (origin, ts) " );
         

         createClass("DbSyncPeers", null, 
                        "nodeid  varchar NOT NULL PRIMARY KEY, " +
                        "url     varchar, " +
                        "item    varchar NOT NULL " );
                        
         
        /* new in schema v. 6 */
        createClass("ServerStats", null, 
                        "time           timestamp without time zone not null, " +
                        "nclients       integer, " +
                        "nloggedin      integer, " +
                        "httpreq        integer, " +
                        "visits         integer, " +
                        "logins         integer, " +
                        "posupdates     integer, " +
                        "aprsposupdates integer, " +
                        "mapupdates     integer ");
                        
        createClass("ServerStart", null, 
                        "time           timestamp without time zone not null ");
                        
                        
        updateQuery("CREATE INDEX ON \"Annotation\" (src,tstart);");
        updateQuery("CREATE INDEX geoindex ON \"PosReport\" USING GIST (position);");
        updateQuery("CREATE INDEX geoindex_s ON \"Signs\" USING GIST (position);");
        
        updateQuery("CREATE INDEX posreport_rtime_idx on \"PosReport\" (rtime);");
        updateQuery("CREATE INDEX posreport_src_time_idx on \"PosReport\" (src, time);");
        updateQuery("CREATE INDEX posreport_time_idx on \"PosReport\" (time);");
         
        updateQuery("CREATE INDEX aprspacket_src_time_idx on \"AprsPacket\" (src, time);");
        updateQuery("CREATE INDEX aprspacket_time_idx on \"AprsPacket\" (time);");
        
        updateQuery("CREATE SEQUENCE signs_seq START WITH 2000 owned by \"Signs\".id;");
        updateQuery("CREATE SEQUENCE jsobject_seq START WITH 5000 owned by \"JsObject\".id;");
        
        updateQuery("INSERT INTO \"SignClass\" (id,name,icon) values (0, 'Default', 'signs/point.png');");
        updateQuery("INSERT INTO \"SignClass\" (id,name,icon) values (1, 'Info', 'signs/info.png');");
        updateQuery("INSERT INTO \"SignClass\" (id,name,icon) values (8, 'Radio installation', 'signs/signal.png');");
        
     }
     
      
     public static void main(String args[])
     {               
         try {
            /* Get properties from configfile */
            if (args.length < 1)
               System.out.println("Usage: Daemon <config-file>");
            Properties config = new Properties(); 
            FileInputStream fin = new FileInputStream(args[0]);
            config.load(fin);
         
            String url   = config.getProperty
              ("db.url", "jdbc:postgresql://[::1]/polaric");   
            String login  = config.getProperty ("db.login");
            String passwd = config.getProperty ("db.passwd");
            System.out.println("url="+url);
            System.out.println("user="+login);

            _db = DriverManager.getConnection(url, login, passwd);
            remove(config);
            installRelations(_db);
            _db.close();                    
         }
         catch (Exception e) {
             e.printStackTrace(System.out);
         }
     }
}
