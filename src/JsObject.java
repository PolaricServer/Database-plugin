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
import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonRawValue;
 
/* JSON object from user's client */
 
public class JsObject implements Serializable {

    public static class User {
        public String userid; 
        public boolean readOnly; 
        public User() 
            {}
        public User(String u, boolean ro) 
            {userid=u; readOnly=ro; }
    }
    

    public String id; 
    public String parent;
    public boolean readOnly=false;
    public boolean noRemove=false; 
    
    @JsonRawValue
    public String data; 
    
    public JsObject(String id, String data)
       { this.id=id; this.data=data; }
    
    public JsObject(String id, boolean ro, String data)
       { this.id=id; this.data=data; this.readOnly=ro; }
       
    public JsObject(String id, boolean ro, boolean nr, String data)
       { this.id=id; this.data=data; this.readOnly=ro; this.noRemove=nr; }
       
    public JsObject(String id, String parent, boolean ro, boolean nr, String data)
       { this.id=id; this.parent=parent; this.data=data; this.readOnly=ro; this.noRemove=nr; }   
}


