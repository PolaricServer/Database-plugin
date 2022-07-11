/* 
 * Copyright (C) 2022 by LA7ECA, Øyvind Hanssen (ohanssen@acm.org)
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
import no.polaric.aprsd.*;
import java.text.*;
import java.util.*;
import java.io.*;



/*
 * Sample and log some data to the database each hour. 
 */
public class DbStatLogger {
    
    private ServerAPI   _api;
    private ServerAPI.Web ws;
    private PluginApi _dbp;
    private long _period = 1000 * 60 * 60;           // 60 minute
    private long _posUpd = TrackerPoint.getPosUpdates();
    private long _aprsPosUpd = TrackerPoint.getAprsPosUpdates();
    private long _req; 
    private long _visits, _logins;  
    private long _mupdates;
    private Timer hb = new Timer();
    
    
    // FIXME: Code to create table and to remove old records
    
    
    public DbStatLogger(ServerAPI api) 
    {     
        try {
            _api = api;
            ws = _api.getWebserver();
            _req = ws.nHttpReq();
            _visits = ws.nVisits();
            _logins = ws.nLogins();
            _mupdates = ws.nMapUpdates();
            _dbp = (PluginApi) api.properties().get("aprsdb.plugin");
        
            _dbp.log().info("DbStatLogger", "Starting statistics data logger");
            doStartup();
            
            /* Schedule periodic task */
            hb.schedule( new TimerTask() 
                { public void run() {       
                    doStatLog();
                } 
            } , _period, _period); 
        }
        catch (Exception e) { _api.log().error("DbStatLogger", ""+e); }
    }
    
    
    
    
    public void doStatLog()
    {          
        StatDBSession db = null;
        try {
            db = new StatDBSession(_dbp.getDB());
            db.addStats(new Date(),
                ws.nClients(), ws.nLoggedin(),
                ws.nHttpReq() - _req,
                ws.nVisits() - _visits,
                ws.nLogins() - _logins,
                TrackerPoint.getPosUpdates() - _posUpd,   
                TrackerPoint.getAprsPosUpdates() - _aprsPosUpd, 
                ws.nMapUpdates() - _mupdates);
        
            _req = ws.nHttpReq();
            _visits = ws.nVisits();
            _logins = ws.nLogins();
            _mupdates = ws.nMapUpdates();
            _posUpd = TrackerPoint.getPosUpdates();
            _aprsPosUpd = TrackerPoint.getAprsPosUpdates();
            
            db.commit();
        }
        catch (Exception e)
            { _api.log().error("DbStatLogger", "Periodic Task: "+e); 
               e.printStackTrace(System.out); }           
        finally { 
            if (db != null) db.close(); 
        }          
    }   
    
    
    public void doStartup()
    {          
        StatDBSession db = null; 
        try {
            db = new StatDBSession(_dbp.getDB());
            db.addStartup(new Date());
            db.commit();
        }
        catch (Exception e)
            { _api.log().error("DbStatLogger", "Boot: "+e); 
               e.printStackTrace(System.out); }           
        finally { 
            if (db!=null) db.close(); 
        }          
    }   
}

