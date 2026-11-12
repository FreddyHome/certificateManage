package com.certmanage.sync;
import java.util.*;
import static com.certmanage.common.Errors.require;
final class SyncData {
 private SyncData(){}
 static String str(Map<String,Object> m,String key,String fallback){Object v=m.get(key);require(v==null||v instanceof String,key+"必须为文本");return Objects.toString(v,fallback);}
 static long id(Map<String,Object> m,String key){require(m.get(key) instanceof Number,key+"必须选择有效记录");return ((Number)m.get(key)).longValue();}
 @SuppressWarnings("unchecked") static Map<String,Object> map(Object v){require(v==null||v instanceof Map,"配置必须为对象");return v==null?new LinkedHashMap<>():new LinkedHashMap<>((Map<String,Object>)v);}
 @SuppressWarnings("unchecked") static List<Map<String,Object>> maps(Object v){require(v==null||v instanceof List,"配置必须为列表");if(v==null)return new ArrayList<>();return ((List<?>)v).stream().map(SyncData::map).toList();}
}
