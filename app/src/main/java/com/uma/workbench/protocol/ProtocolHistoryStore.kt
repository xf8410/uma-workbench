package com.uma.workbench.protocol

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ProtocolHistoryRecord(
 val id:String,val timestamp:Long,val channel:SendChannel,val endpoint:String,val sid:String?,val viewerId:Long?,val requestHeaders:List<ProtocolHeader>,val requestBody:String,val requestBodyEncrypted:Boolean,val httpStatus:Int?,val protocolCode:Int?,val responseHeaders:List<ProtocolHeader>?,val responseBody:String?,val responseBodyDecrypted:String?,val latencyMs:Long?,val success:Boolean?,val error:String?
){
 constructor(id:String,timestamp:Long,channel:SendChannel,endpoint:String,sid:String?,viewerId:Long?,requestHeaders:Map<String,String>,requestBody:String,requestBodyEncrypted:Boolean,httpStatus:Int?,protocolCode:Int?,responseHeaders:Map<String,String>?,responseBody:String?,responseBodyDecrypted:String?,latencyMs:Long?,success:Boolean?,error:String?):this(id,timestamp,channel,endpoint,sid,viewerId,ProtocolHeaders.fromMap(requestHeaders),requestBody,requestBodyEncrypted,httpStatus,protocolCode,responseHeaders?.let(ProtocolHeaders::fromMap),responseBody,responseBodyDecrypted,latencyMs,success,error)
 fun toLogEntry():ProtocolLogEntry{val request=GameRequest(GameEndpoint.fromPath(endpoint),sid,viewerId,requestBody,requestBodyEncrypted,ProtocolHeaders.compatibilityMap(requestHeaders),timestamp,endpoint,requestHeaders);val response=httpStatus?.let{GameResponse(it,ProtocolStatusCode.fromCode(protocolCode?:it),ProtocolHeaders.compatibilityMap(responseHeaders.orEmpty()),responseBody.orEmpty(),responseBodyDecrypted,latencyMs?:0,timestamp,success==true,responseHeaders.orEmpty())};return ProtocolLogEntry(timestamp,request,response,error,channel)}
 companion object{fun from(entry:ProtocolLogEntry)=ProtocolHistoryRecord(UUID.randomUUID().toString(),entry.timestamp,entry.channel,entry.request.rawEndpoint,entry.request.sid,entry.request.viewerId,entry.request.headerEntries,entry.request.body,entry.request.bodyEncrypted,entry.response?.statusCode,entry.response?.protocolCode?.code,entry.response?.headerEntries,entry.response?.body,entry.response?.bodyDecrypted,entry.response?.latencyMs,entry.response?.success,entry.error)}
}

object ProtocolHeaderCodec{
 private val json=Json
 fun encode(headers:List<ProtocolHeader>)=buildJsonArray{headers.forEach{h->add(buildJsonObject{put("name",h.name);put("value",h.value)})}}.toString()
 fun encode(headers:Map<String,String>)=encode(ProtocolHeaders.fromMap(headers))
 fun decode(encoded:String):List<ProtocolHeader>=when(val root=json.parseToJsonElement(encoded)){is JsonArray->root.mapIndexed{i,item->val o=item as? JsonObject?:error("header[$i] must be object");ProtocolHeader(o["name"]?.jsonPrimitive?.content?:error("header[$i].name missing"),o["value"]?.jsonPrimitive?.content?:error("header[$i].value missing"))};is JsonObject->root.entries.map{ProtocolHeader(it.key,it.value.jsonPrimitive.content)};else->error("headers must be JSON array or legacy object")}
}

class ProtocolHistoryStore(context:Context){
 private val helper=ProtocolHistoryOpenHelper(context.applicationContext)
 suspend fun append(entry:ProtocolLogEntry):ProtocolHistoryRecord=withContext(Dispatchers.IO){append(ProtocolHistoryRecord.from(entry))}
 suspend fun append(record:ProtocolHistoryRecord):ProtocolHistoryRecord=withContext(Dispatchers.IO){helper.writableDatabase.insertOrThrow(TABLE,null,record.toValues());record}
 suspend fun get(id:String):ProtocolHistoryRecord?=withContext(Dispatchers.IO){helper.readableDatabase.query(TABLE,COLUMNS,"id = ?",arrayOf(id),null,null,null).use{if(it.moveToFirst())it.toRecord() else null}}
 suspend fun all():List<ProtocolHistoryRecord> = withContext(Dispatchers.IO){helper.readableDatabase.query(TABLE,COLUMNS,null,null,null,null,"timestamp ASC, rowid ASC").use{c->buildList{while(c.moveToNext())add(c.toRecord())}}}
 suspend fun compare(firstId:String,secondId:String):ProtocolHistoryComparison?=withContext(Dispatchers.IO){val first=get(firstId)?:return@withContext null;val second=get(secondId)?:return@withContext null;ProtocolHistoryComparison(first,second,ProtocolPayloadDiff.compare(first.requestBody,second.requestBody),ProtocolPayloadDiff.compare(first.responseBody.orEmpty(),second.responseBody.orEmpty()))}
 private fun ProtocolHistoryRecord.toValues()=ContentValues().apply{put("id",id);put("timestamp",timestamp);put("channel",channel.name);put("endpoint",endpoint);putNullable("sid",sid);putNullable("viewer_id",viewerId);put("request_headers",ProtocolHeaderCodec.encode(requestHeaders));put("request_body",requestBody);put("request_body_encrypted",if(requestBodyEncrypted)1 else 0);putNullable("http_status",httpStatus);putNullable("protocol_code",protocolCode);putNullable("response_headers",responseHeaders?.let(ProtocolHeaderCodec::encode));putNullable("response_body",responseBody);putNullable("response_body_decrypted",responseBodyDecrypted);putNullable("latency_ms",latencyMs);putNullable("success",success?.let{if(it)1 else 0});putNullable("error",error)}
 private fun Cursor.toRecord()=ProtocolHistoryRecord(getString(index("id")),getLong(index("timestamp")),SendChannel.valueOf(getString(index("channel"))),getString(index("endpoint")),nullableString("sid"),nullableLong("viewer_id"),ProtocolHeaderCodec.decode(getString(index("request_headers"))),getString(index("request_body")),getInt(index("request_body_encrypted"))!=0,nullableInt("http_status"),nullableInt("protocol_code"),nullableString("response_headers")?.let(ProtocolHeaderCodec::decode),nullableString("response_body"),nullableString("response_body_decrypted"),nullableLong("latency_ms"),nullableInt("success")?.let{it!=0},nullableString("error"))
 private fun Cursor.index(n:String)=getColumnIndexOrThrow(n);private fun Cursor.nullableString(n:String)=index(n).let{if(isNull(it))null else getString(it)};private fun Cursor.nullableInt(n:String)=index(n).let{if(isNull(it))null else getInt(it)};private fun Cursor.nullableLong(n:String)=index(n).let{if(isNull(it))null else getLong(it)}
 private fun ContentValues.putNullable(n:String,v:String?){if(v==null)putNull(n) else put(n,v)};private fun ContentValues.putNullable(n:String,v:Int?){if(v==null)putNull(n) else put(n,v)};private fun ContentValues.putNullable(n:String,v:Long?){if(v==null)putNull(n) else put(n,v)}
 companion object{private const val TABLE="protocol_history";private val COLUMNS=arrayOf("id","timestamp","channel","endpoint","sid","viewer_id","request_headers","request_body","request_body_encrypted","http_status","protocol_code","response_headers","response_body","response_body_decrypted","latency_ms","success","error")}
 private class ProtocolHistoryOpenHelper(c:Context):SQLiteOpenHelper(c,"uma-protocol-history.db",null,1){override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE protocol_history (id TEXT NOT NULL PRIMARY KEY,timestamp INTEGER NOT NULL,channel TEXT NOT NULL,endpoint TEXT NOT NULL,sid TEXT,viewer_id INTEGER,request_headers TEXT NOT NULL,request_body TEXT NOT NULL,request_body_encrypted INTEGER NOT NULL,http_status INTEGER,protocol_code INTEGER,response_headers TEXT,response_body TEXT,response_body_decrypted TEXT,latency_ms INTEGER,success INTEGER,error TEXT)");db.execSQL("CREATE INDEX protocol_history_timestamp ON protocol_history(timestamp)");db.execSQL("CREATE INDEX protocol_history_endpoint ON protocol_history(endpoint)")};override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit}
}
data class ProtocolHistoryComparison(val first:ProtocolHistoryRecord,val second:ProtocolHistoryRecord,val requestBody:List<ProtocolDiffEntry>,val responseBody:List<ProtocolDiffEntry>)
