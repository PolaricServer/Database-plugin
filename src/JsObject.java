 
 
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
}


