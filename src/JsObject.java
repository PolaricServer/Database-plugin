 
 
package no.polaric.aprsdb;
import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonRawValue;
 
/* JSON object from user's client */
 
public class JsObject implements Serializable {
    public long id; 
    public boolean readOnly=false;
    
    @JsonRawValue
    public String data; 
    
    public JsObject(long id, String data)
       { this.id=id; this.data=data; }
    
    public JsObject(long id, boolean ro, String data)
       { this.id=id; this.data=data; this.readOnly=ro; }
}


