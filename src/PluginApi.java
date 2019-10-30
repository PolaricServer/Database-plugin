
package no.polaric.aprsdb;
import no.polaric.aprsd.*;
 
 
 
public interface PluginApi {
    public Logfile log();
    public MyDBSession getDB();
    public MyDBSession getDB(boolean autocommit);
    public void saveItem(TrackerPoint tp);
}
