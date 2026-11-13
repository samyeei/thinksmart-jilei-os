package local.jile.v5;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Credentials are accepted only in a bounded POST body, never in URLs or logs. */
final class PasswordChangeForm {
 static final int MAX_LENGTH=6;
 final String current,next;
 private PasswordChangeForm(String current,String next){this.current=current;this.next=next;}
 static void validateNew(String value){
  if(value==null||!ParentPin.valid(value))throw new IllegalArgumentException("新密码须为六位数字");
 }
 static PasswordChangeForm parse(byte[] body,String contentType){
  if(contentType==null||!contentType.split(";",2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded"))throw new IllegalArgumentException("请通过修改密码表单提交");
  if(body==null||body.length==0||body.length>4096)throw new IllegalArgumentException("修改密码表单无效");
  Map<String,String> fields=new HashMap<>();
  for(String pair:new String(body,StandardCharsets.UTF_8).split("&",-1)){
   int at=pair.indexOf('=');if(at<=0)throw new IllegalArgumentException("修改密码表单无效");
   String key=pair.substring(0,at);
   if(!key.equals("current_password")&&!key.equals("new_password")&&!key.equals("confirm_password"))throw new IllegalArgumentException("修改密码表单无效");
   String value;try{value=URLDecoder.decode(pair.substring(at+1),"UTF-8");}catch(Exception invalid){throw new IllegalArgumentException("修改密码表单无效");}
   if(fields.put(key,value)!=null)throw new IllegalArgumentException("修改密码表单无效");
  }
  if(fields.size()!=3)throw new IllegalArgumentException("请填写当前密码、新密码和确认密码");
  String current=fields.get("current_password"),next=fields.get("new_password");
  if(current.isEmpty()||current.length()>MAX_LENGTH)throw new IllegalArgumentException("当前密码格式无效");
  validateNew(next);
  if(!next.equals(fields.get("confirm_password")))throw new IllegalArgumentException("两次输入的新密码不一致");
  if(next.equals(current))throw new IllegalArgumentException("新密码不能与当前密码相同");
  return new PasswordChangeForm(current,next);
 }
}
