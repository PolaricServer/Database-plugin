/* 
 * Copyright (C) 2013 by Oyvind Hanssen (ohanssen@acm.org)
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
     
     /* Schema version - increase when changing schema and provide upgrade method */
     private static final int _VERSION = 1; 
     
         
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
         removeClass("AprsMessage");   
         removeClass("Mission");
         removeClass("Tracker");
         removeClass("JsObject");
         removeClass("SignClass");
         removeClass("Signs");
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
                                                     
                                                     
        createClass("Mission", null, 
                        "src    varchar(20) not null, " +
                        "alias  varchar(30), " +
                        "icon   varchar, " +
                        "tstart  timestamp without time zone not null, "  +
                        "tend    timestamp without time zone, "  + /* NULL means still active */
                        "descr  text, " +
                        "PRIMARY KEY (src, tstart) " );
                
                
        createClass("Tracker", null, 
                        "id      varchar(20) not null PRIMARY KEY, " +
                        "userid  varchar(20), " +
                        "alias   varchar(30), " +
                        "icon    varchar " );
                        
        /* New in schema v. 4 */                
        createClass("TrTags", null, 
                        "userid  varchar(20) not null, " +
                        "tag     varchar(20) not null" );
                               
                               
        createClass("JsObject", null, 
                        "id      SERIAL PRIMARY KEY, " +
                        "tag     varchar(20), " +
                        "data    text" );
                        
        /* new in schema v. 2 */
        createClass("ObjectAccess", null, 
                        "id       integer REFERENCES \"JsObject\" (id) ON DELETE CASCADE, " +
                        "readonly boolean DEFAULT 'false', " + 
                        "userid   varchar(20) " );
                                
        createClass("SignClass", null, 
                        "id      SERIAL PRIMARY KEY, "+
                        "name    text, "+
                        "icon    text ");
                               
        createClass("Signs", null,
                        "id          SERIAL PRIMARY KEY, "+
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
                               
            
        updateQuery("CREATE INDEX geoindex ON \"PosReport\" USING GIST (position);");
        updateQuery("CREATE INDEX geoindex_s ON \"Signs\" USING GIST (position);");
        updateQuery("CREATE INDEX posreport_rtime_idx on \"PosReport\" (rtime);");
        updateQuery("CREATE INDEX posreport_time_src_idx on \"PosReport\" (time,src);");
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
