 
 
 
 /********************************************************************************************
  *  Heard points via
  ********************************************************************************************/
 
 var heard = null;
 
 function searchHeardPoints(call) {
    heard = new heardPoints(call); 
 }
 
 
 
 function heardPoints(call)
 {
   var t = this; 
   this.call = ""; 
   this.color = "e11";
   this.fromdate = null;  
   this.todate = null; 
   this.hist = []; 

   
   if (storage['polaric.db.heard'] != null)
     this.hist = JSON.parse(storage['polaric.db.heard']);
   
   if (ses_storage['polaric.db.heard.call'] != null)
     this.fromdate = ses_storage['polaric.db.heard.from'];
   
   if (ses_storage['polaric.db.heard.from'] != null)
     this.fromdate = JSON.parse(ses_storage['polaric.db.heard.from']);
   
   
   if (t.fromdate == null)
     t.fromdate = formatDate(new Date());
   if (t.todate == null || t.todate == '-')
     t.todate = formatDate(new Date()); 
   if (call != null)
     t.call = call;
   
   
   var x = popupwindow(document.getElementById("anchor"),
        ' <h1>Søk hørte punkter via...</h1>' +
        'OBS: Søk over store datamengder kan ta lang tid.<hr><form>'+
        ' <span class="sleftlab">Stasjon: </span><input type="text"  size="10" id="findcall" value="'
            + t.call + '"/><br> '+
        
        ' <span class="sleftlab">Tid start: </span><input type="text"  size="10" id="tfrom" value="'
           + t.fromdate + 
        '"/> <br> '+
        ' <span class="sleftlab">Tid slutt: </span><input type="text" size="10" id="tto" value="'
           + t.todate + 
        '"/>&nbsp; <input type="checkbox" id="ttoopen"> Åpen slutt <br> '+
        
        
        ' <span class="sleftlab">Farge: </span>'+
        ' <select name="colorpicker" id="colorsel">' +
        '     <option value="#e11">Rød</option>' +
        '     <option value="#11e">Blå</option>' +
        '     <option value="#080">Grønn</option>' +
        '     <option value="#222">Svart</option>' +
        ' </select> <br>' +    
        
        
        '<hr>'+
        '<div id="searchlist"></div>' +
        '<hr>'+
        ' <input id="searchbutton" type="button"' +
        ' value="Søk" />'+ 
        
        ' <input id="addbutton" title="Legg søk til liste" type="button"' +
        ' value="Legg til" />'+
        
        ' <input id="showallbutton" title="Vis alle søk i liste" type="button"' +
        ' value="Vis alle" />'+

        ' <input id="clearbutton" title="Nullstill liste" type="button"' +
        ' value="Nullstill" />'+
        
        '</form><br>', 50, 70, null) 
   
   t.displayList(); 
   

   $('select[name="colorpicker"]').simplecolorpicker({
        theme: 'regularfont'
    }).on('change', function() {
        t.color = $('select[name="colorpicker"]').val().substring(1);
    });
   
   
   $('#ttoopen').change( function() {
     if ($('#ttoopen').attr('checked'))
       $('#tto').prop('disabled',true);
     else 
       $('#tto').removeProp('disabled');
   });
   $('#ttoopen').attr('checked', true);
   $('#tto').prop('disabled',true);
   
   
   $('#searchbutton').click( function() {
     t.getItem();
     t.getPointsXmlData( t.call.toUpperCase(), t.fromdate, t.todate, t.color ); 
     t.saveList();
   });
   
   $('#addbutton').click( function() {
     t.getItem();
     t.call = t.call.toUpperCase();
     t.hist.push({ call:t.call, color:t.color, fromdate:t.fromdate, todate:t.todate, 
       fromtime: t.fromtime, totime: t.totime});
     t.displayList(); 
     t.saveList();
   });
   
   $('#showallbutton').click( function() {
     t.showAll();
   });
   
   $('#clearbutton').click( function() {
     t.hist = [];
     t.displayList(); 
     t.saveList();
   });
   
   $('#tfrom,#tto').datepicker({ dateFormat: 'yy-mm-dd' });
   $(x).resizable(); 
 
}
 
 
 
heardPoints.prototype.saveList = function() {
   storage['polaric.db.heard'] = JSON.stringify(this.hist);
   ses_storage['polaric.db.heard.call'] = this.call;
   ses_storage['polaric.db.heard.from'] = JSON.stringify(this.fromdate);
}



heardPoints.prototype.displayList = function()
{
  var txt = "";
  var t = this; 
  for (i=0; i<this.hist.length; i++) {
    txt += '<img title="remove" src="images/edit-delete.png" height="14" id="deleteItem'+i+'"> '+
    '<img title="edit" src="images/edit.png" height="14" id="editItem'+i+'"> '+
    this.hist[i].call + ' : ' + this.hist[i].fromdate+" " + ' - ' +
    this.hist[i].todate + ' ' + '&nbsp;<span style="background: #'+this.hist[i].color+'">&nbsp;&nbsp;&nbsp;</span> <br>';
  }
  document.getElementById("searchlist").innerHTML = txt; 
  
  for (i=0; i<this.hist.length; i++)
  { 
    $('#deleteItem'+i).click(handleDelete(i)) ;
    $('#editItem'+i).click(handleEdit(i)) ;
  }
  
  function handleDelete(i) { return function() { t.deleteItem(i); }}
  function handleEdit(i) { return function() { t.editItem(i); }}
}



heardPoints.prototype.getItem = function()
{
   var t = this;
   t.call     = $('#findcall').val();
   t.fromdate = $('#tfrom').val();
   if ($('#ttoopen').is(':checked')) 
      t.todate = '-';
   else
      t.todate   = $('#tto').val();    
}





Array.prototype.remove = function(from, to) {
  var rest = this.slice((to || from) + 1 || this.length);
  this.length = from < 0 ? this.length + from : from;
  return this.push.apply(this, rest);
};




heardPoints.prototype.deleteItem = function(idx)
{
   this.hist.remove(idx);
   this.displayList();
   this.saveList();
}



heardPoints.prototype.editItem = function(idx)
{
   var t = this;
   $('#findcall').val(t.hist[idx].call);
   $('#colorsel').val(t.hist[idx].color);
   $('select[name="colorpicker"]').simplecolorpicker('selectColor', '#'+t.hist[idx].color);
   
   $('#tfrom').val(t.hist[idx].fromdate);
   if (t.hist[idx].todate == '-') {
      $('#ttoopen').attr('checked', true);
      $('#tto').prop('disabled',true);
      $('#tto').val(formatDate(new Date()));
   }
   else {
      $('#ttoopen').attr('checked', false);
      $('#tto').removeProp('disabled');
      $('#tto').val(t.hist[idx].todate);
   }
   this.getItem(); 
   this.deleteItem(idx);
   this.saveList();
}



heardPoints.prototype.extentQuery = function()
{
   var ext = myKaMap.getGeoExtents();
   var flt = "";
   if (filterProfiles.selectedProf() != null)
      flt = "&filter="+filterProfiles.selectedProf();
     return "x1="  + roundDeg(ext[0]) + "&x2="+ roundDeg(ext[1]) +
            "&x3=" + roundDeg(ext[2]) + "&x4="+ roundDeg(ext[3]) + flt ;
}



heardPoints.prototype.showAll = function()
{
   abortCall(lastXmlCall);
   mapupdate_suspend(120*1000);
   if (myOverlay != null) 
      myOverlay.removePoint(); 

   for (i=0; i < this.hist.length; i++)  
      myOverlay.loadXml('srv/hpoints?'+this.extentQuery() + '&scale='+currentScale+
       '&station=' + this.hist[i].call + '&tfrom='+ this.hist[i].fromdate + 
       '&tto='+  this.hist[i].todate + '&color='+this.hist[i].color + (clientses!=null? '&clientses='+clientses : ""));
}



heardPoints.prototype.getPointsXmlData = function(stn, tfrom, tto, color)
{ 
   abortCall(lastXmlCall);
   mapupdate_suspend(120*1000);
   if (myOverlay != null) 
      myOverlay.removePoint(); 
      myOverlay.loadXml('srv/hpoints?'+this.extentQuery() + '&scale='+currentScale+
       '&station=' + stn + '&tfrom='+ tfrom + '&tto='+tto+ '&color='+color + (clientses!=null? '&clientses='+clientses : ""));
}


