package com.sinosoft.lis.connector.impl;

import com.sinosoft.lis.cloud.LCAddressPojo;
import com.sinosoft.lis.cloud.LCContPojo;
import com.sinosoft.lis.cloud.LCInsuredPojo;                
import com.sinosoft.lis.cloud.LCPolPojo;
import com.sinosoft.lis.cloud.common.core.microservice.TradeInfo;
import com.sinosoft.lis.cloud.common.redis.RedisCommonDao;
import com.sinosoft.lis.cloud.entity.Lmriskapp;
import com.sinosoft.lis.cloud.pubfun.PubFun;
import com.sinosoft.lis.cloud.pubfun.PubFun1;
import com.sinosoft.lis.connector.AbsConnector;
import com.sinosoft.lis.dto.yb.request.ReqDto;
import com.sinosoft.lis.dto.yb.response.RespDto;
import com.sinosoft.lis.feignClient.YbClient;
import com.sinosoft.lis.util.PdfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.io.File;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.crypto.Cipher;

/**
 * description: 亿保 授权pdf文件上传阿里云备份接口 风控仅针对被保人进行
 * author: xueqiang du
 * date: 2022/5/10 13:47
 * version: 1.0
 */
@Component
public class YBConnector extends AbsConnector {

    private static Logger logger = LoggerFactory.getLogger("com.sinosoft.lis.connector.impl.YBConnector");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RedisCommonDao redisCommonDao;

    @Autowired
    private YbClient ybClient;
    @Override
    public TradeInfo call(TradeInfo tradeInfo)  {

        LCContPojo lcContPojo = (LCContPojo)tradeInfo.getData ( "LCContPojo" );
        LCPolPojo lcPolPojo = (LCPolPojo) tradeInfo.getData ( "LCPolPojo" );
        LCAddressPojo lcAddressPojo = (LCAddressPojo) tradeInfo.getData ( "LCAddressPojoAppnt" );
        LCInsuredPojo lcInsuredPojo = (LCInsuredPojo)tradeInfo.getData ( "LCInsuredPojo" );
        //生成请求序列号
        String clientSerialNo = new SimpleDateFormat ("yyyyMMddHHmmssSSS").format(new Date ())+ PubFun1.CreateMaxNo("PicusTransNo", 13);
        //根据产品编码获取产品名称
        String riskName = "";
        List<Lmriskapp> lmriskapps = redisCommonDao.findByIndexKeyRelaDBHash ( Lmriskapp.class, "riskcode", lcPolPojo.getRiskCode () );
        if (lmriskapps != null && !"".equals ( lmriskapps ) && !lmriskapps.isEmpty ()){
            riskName = lmriskapps.get ( 0 ).getRiskname ();
        }
        Map<String, Object> data = new HashMap<String, Object> ();
        data.put("TransactionNo", clientSerialNo);
        data.put("ContNo", lcContPojo.getContNo ());
        data.put("CustomerNo",  lcContPojo.getInsuredNo ());
        data.put("AppntName", lcContPojo.getInsuredName ());
        data.put("CurrDate", PubFun.getCurrentDate ());
        data.put("InsureProduct", riskName);
        data.put("RiskControlCA", lcContPojo.getInsuredName ());
        //生成的pdf文件存放的路径  path + "YbCyAuthFile" + data.get("TransactionNo") + ".pdf";
        String pdfPath = applicationContext.getEnvironment ().getProperty ( "feignCli.request.yb.pdfPath" );
        pdfPath += "YbCyAuthFile"+ clientSerialNo + ".pdf";
        String encodeBase64String = "";
        try {
            pdfPath = PdfUtils.createPdf (pdfPath,data);
            File file = new File (pdfPath);
            if (!file.exists()) {
                logger.info("生成pdf授权文件出错,未能正确生成相应的pdf，请确认信息是否正确！！！");
                tradeInfo.addError ( "生成pdf授权文件出错,未能正确生成相应的pdf，请确认信息是否正确！！！" );
                return tradeInfo;
            }
            String file2base64 = PdfUtils.file2base64 ( file );
            encodeBase64String = URLEncoder.encode(file2base64, "UTF-8");
            logger.info ( "生成的pdf文件的字符串内容为---"+encodeBase64String );
        } catch (Exception e) {
            logger.info("生成pdf授权文件出错,未能正确生成相应的pdf，请确认信息是否正确！！！"+e.getMessage ());
            tradeInfo.addError ( "生成pdf授权文件出错,未能正确生成相应的pdf，请确认信息是否正确！！！具体错误信息为"+e.getMessage () );
        }

        Map<String, Object> map = dealSignData ( clientSerialNo, tradeInfo );
        map.put ( "encodeBase64String",encodeBase64String );
        //根据key进行排序
        List<Map.Entry<String, Object>> entries = new ArrayList<Map.Entry<String, Object>>(map.entrySet());
        Collections.sort ( entries, new Comparator<Map.Entry<String, Object>> () {
            @Override
            public int compare(Map.Entry<String, Object> o1, Map.Entry<String, Object> o2) {
                return o1.getKey().compareTo(o2.getKey());
            }
        });

        String CASign = "";
        //循环遍历集合，当map对应的值不为空时，进行拼接
        for (int i = 0; i < entries.size (); i++) {
            if(entries.get ( i ).getValue () != null && !"".equals ( entries.get ( i ).getValue () )){
                CASign += entries.get ( i ).getValue ()+"_";
            }
        }
        //获取配置的秘钥信息
        String property = applicationContext.getEnvironment ().getProperty ( "feignCli.request.yb.app_secret" );
        CASign+= property;
        logger.info ( "拼接的加密之前的digest-------------"+CASign );
        try {
            CASign = DigestUtils.md5DigestAsHex ( CASign.getBytes ( "UTF8" ) ).toUpperCase ();
        }catch (Exception e){
            logger.error ( "加密出现错误，错误信息为-------"+e.getMessage () );
        }
        logger.info ( "加密之后的digest-------------"+CASign );
        ReqDto reqDto = new ReqDto ();
        reqDto.setInsureProduct ( lcPolPojo.getRiskCode () )
                .setClientSerialNo ( clientSerialNo )  //
                .setChannel (1)
                .setAuthFileBase64 (encodeBase64String)  //授权文件对应的内容
                .setClientNo ( lcContPojo.getInsuredNo () )
                .setPayYearLimit (lcPolPojo.getYears())
                .setSocialIdentity("Y".equals ( lcInsuredPojo.getSoicalState () )?1:0);
        reqDto.setFirstPaymentAmount(lcPolPojo.getPrem ());

        reqDto.setAuthFileType ( "pdf" );
        reqDto.setInsureCityM6002 (lcAddressPojo.getCity ());
        reqDto.setClientRole (1); //0：投保人，1：被保人，2：其他
        reqDto.setPhone ( lcAddressPojo.getPhone () )
                .setName ( lcContPojo.getAppntName () )
                .setInsurePolicy ( Double.valueOf ( (String)tradeInfo.getData ( "riskPrem" )))
                .setDigest ( CASign )
                .setPaymentMethod ( Integer.parseInt ( lcPolPojo.getPayMode () ) )
                .setIdNum ( lcContPojo.getAppntIDNo () )
                .setTimestamp (String.valueOf (System.currentTimeMillis()));

        RespDto respDto = null;
        try{
            respDto = ybClient.sendRequest ( reqDto );
        }catch (Exception e){
            tradeInfo.addError ( "远程调用亿保出现错误，具体信息为"+e.getMessage () );
            return tradeInfo;
        }
        if(respDto != null && !"".equals ( respDto )){
            if(respDto.getStatus () != 0){
                tradeInfo.addData ( "code",respDto.getStatus () );
                tradeInfo.addData ( "msg",getMsg ( respDto.getStatus () ) );
                tradeInfo.addData ( "flag",false );
                return tradeInfo;
            }
            tradeInfo.addData ( "code","0");
            tradeInfo.addData ( "flag",true );
            tradeInfo.addData ( "msg",respDto.getInfo () );

        }
        tradeInfo.addData ( "respDto",respDto );
        return tradeInfo;
    }

    private Map<String,Object> dealSignData(String serilno,TradeInfo tradeInfo){
        LCContPojo lcContPojo = (LCContPojo)tradeInfo.getData ( "LCContPojo" );
        LCPolPojo lcPolPojo = (LCPolPojo) tradeInfo.getData ( "LCPolPojo" );
        LCInsuredPojo lcInsuredPojo = (LCInsuredPojo)tradeInfo.getData ( "LCInsuredPojo" );
        LCAddressPojo lcAddressPojo = (LCAddressPojo) tradeInfo.getData ( "LCAddressPojoAppnt" );
        Map<String,Object> map = new HashMap<> (  );
        map.put ( "insureProduct",lcPolPojo.getRiskCode () );
        map.put ( "clientSerialNo",serilno );
        map.put ( "channel","1");
        map.put ( "clientNo",lcContPojo.getInsuredNo () );
        map.put ( "payYearLimit",lcPolPojo.getRiskCode () );
        map.put ( "socialIdentity",lcInsuredPojo.getSoicalState () );
        map.put ( "firstPaymentAmount",lcPolPojo.getPrem () );
        map.put ( "authFileType","pdf" );
        map.put ( "insureCityM6002",lcAddressPojo.getCity () );
        map.put ( "clientRole","1");  //被保人
        map.put ( "phone",lcAddressPojo.getPhone () );
        map.put ( "name",lcInsuredPojo.getName ());
        map.put ( "insurePolicy",tradeInfo.getData ( "riskPrem" ));  //风险保额
        map.put ( "paymentMethod",lcPolPojo.getPayMode ());
        map.put ( "idNum",lcInsuredPojo.getIDNo ());
        map.put ( "timestamp",System.currentTimeMillis ());
        return map;
    }

    private String getMsg(int code){
        String msg = "";
        switch (code){
            case -101:
                msg = "验证签名错误";
                break;
            case -102:
                msg = "接口单日访问量过大";
                break;
            case -103:
                msg = "账号余额不足";
                break;
            case -201:
                msg = "缺少必选参数 ";
                break;
            case -202:
                msg = "参数格式错误";
                break;
            case -204:
                msg = "请求格式错误";
                break;
            case -301:
                msg = "系统异常";
                break;
                default: msg="处理成功";
        }
        return msg;
    }

    public static void main(String[] args) {
        Map<String ,Object> map = new HashMap<> (  );
        map.put ( "insureProduct","5" );
        map.put ( "clientSerialNo",4444 );
        map.put ( "channel","1");
        map.put ( "clientNo","" );
        map.put ( "payYearLimit","ff" );
        map.put ( "socialIdentity","Y" );
        map.put ( "firstPaymentAmount",100.0 );
        map.put ( "authFileType","pdf" );
        map.put ( "insureCityM6002","970877");
        map.put ( "clientRole","1");  //被保人
        map.put ( "phone","1944444" );
        map.put ( "name","哈哈哈");
        map.put ( "insurePolicy","");  //风险保额
        map.put ( "paymentMethod","1");
        map.put ( "idNum","66666666");
        map.put ( "timestamp",System.currentTimeMillis ());

        List<Map.Entry<String, Object>> entries = new ArrayList<Map.Entry<String, Object>>(map.entrySet());
        Collections.sort ( entries, new Comparator<Map.Entry<String, Object>> () {
            @Override
            public int compare(Map.Entry<String, Object> o1, Map.Entry<String, Object> o2) {
                return o1.getKey().compareTo(o2.getKey());
            }
        });

        String sign = "";
        for (int i = 0; i < entries.size (); i++) {
            if(entries.get ( i ).getValue () != null && !"".equals ( entries.get ( i ).getValue () )){
                sign += entries.get ( i ).getValue ()+"_";
            }
        }
        System.out.println (sign);
        entries.forEach ( l-> System.out.println (l) );
    }
}
