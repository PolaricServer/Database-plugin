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
import  java.util.*;
import  no.polaric.aprsd.*;
import  no.polaric.aprsd.point.*;
import  java.io.Serializable;


public class Tracker
{
    public static class Info implements Serializable {
        public String id, user, alias, icon;
        public boolean active; 
        public Date lastHrd;
        
        public Info() 
            {};
        public Info(String i, String u, String a, String ic)
            {id=i; user=u; alias=a; icon=ic; }
    }
    
    
    public Info info;
    private TrackerPoint st; 
  
    public boolean isActive() 
        { return st != null; }
     
    public TrackerPoint getStation() 
        { return st; }
     
    public String getAlias()
        { return (isActive() ? st.getAlias() : info.alias); }
     
    public String getIcon() 
        { return (isActive() ? st.getIcon() : info.icon); }
     
     
    public Tracker (StationDB db, String i, String u, String a, String ic) {
        info = new Info(i,u,a,ic);
        st = db.getItem(info.id, null, false);
        info.active = isActive();
        info.lastHrd = (isActive() ? getStation().getLastChanged() : null);
    }
    
    public Tracker (StationDB db, Info inf)
        { info = inf; st = db.getItem(inf.id, null, false); }
  
}
   
