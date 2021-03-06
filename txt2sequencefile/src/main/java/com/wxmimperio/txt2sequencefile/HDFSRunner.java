package com.wxmimperio.txt2sequencefile;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.gson.JsonObject;
import org.apache.avro.Schema;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.io.*;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.URI;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by weiximing.imperio on 2016/7/25.
 */
public class HDFSRunner {
    private static final Logger LOG = LoggerFactory.getLogger(HDFSRunner.class);
    private static final String hdfsUri = "";

    private static final String DEFAULTFS = "fs.defaultFS";
    private static final String DFS_FAILURE_ENABLE = "dfs.client.block.write.replace-datanode-on-failure.enable";
    private static final String DFS_FAILURE_POLICY = "dfs.client.block.write.replace-datanode-on-failure.policy";
    private static final String DFS_SESSION_TIMEOUT = "dfs.client.socket-timeout";
    private static final String DFS_TRANSFER_THREADS = "dfs.datanode.max.transfer.threads";
    private static final String DFS_SUPPORT_APPEND = "dfs.support.append";
    private static final String CORE_SITE_XML = "core-site.xml";
    private static final String HDFS_SITE_XML = "hdfs-site.xml";
    private static final String MAPRED_SITE_XML = "mapred-site.xml";
    private static final String YARN_SITE_XML = "mapred-site.xml";


    static Configuration config() {
        Configuration conf = new Configuration();
        conf.addResource(CORE_SITE_XML);
        conf.addResource(HDFS_SITE_XML);
        conf.setBoolean(DFS_SUPPORT_APPEND, true);
        conf.setBoolean(DFS_FAILURE_ENABLE, true);
        conf.set(DFS_FAILURE_POLICY, "NEVER");
        conf.set("fs.hdfs.impl", "org.apache.hadoop.hdfs.DistributedFileSystem");
        return conf;
    }

    public static synchronized boolean append2SequenceFile(String filePath, List<String> buffer) {
        boolean flags = false;
        FSDataOutputStream outStream = null;
        FileSystem hdfs;
        SequenceFile.Writer writer = null;
        try {
            Path path = new Path(filePath);
            hdfs = FileSystem.get(URI.create(hdfsUri), config());
            if (hdfs.isFile(path)) {
                outStream = hdfs.append(path);
            } else {
                outStream = hdfs.create(path);
            }
            Text value = new Text();
            BytesWritable key = new BytesWritable();
            BytesWritable EMPTY_KEY = new BytesWritable();

            writer = SequenceFile.createWriter(
                    config(),
                    outStream,
                    key.getClass(),
                    value.getClass(),
                    SequenceFile.CompressionType.BLOCK,
                    null);

            int index = 0;
            for (String str : buffer) {
                value.set(str);
                writer.append(EMPTY_KEY, value);
                index++;
            }
            writer.sync();
            hflushOrSync(outStream);

            System.out.println("write size=" + index);
            flags = true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeStream(writer);
            IOUtils.closeStream(outStream);
        }
        return flags;
    }

    protected static void hflushOrSync(FSDataOutputStream os) throws IOException {
        try {
            // At this point the refHflushOrSync cannot be null,
            // since register method would have thrown if it was.
            reflectHflushOrSync(os).invoke(os);
        } catch (InvocationTargetException e) {
            String msg = "Error while trying to hflushOrSync!";
            e.printStackTrace();
            Throwable cause = e.getCause();
            if (cause != null && cause instanceof IOException) {
                throw (IOException) cause;
            }
        } catch (Exception e) {
            String msg = "Error while trying to hflushOrSync!";
            e.printStackTrace();
        }
    }

    private static Method reflectHflushOrSync(FSDataOutputStream os) {
        Method m = null;
        if (os != null) {
            Class<?> fsDataOutputStreamClass = os.getClass();
            try {
                m = fsDataOutputStreamClass.getMethod("hflush");
            } catch (NoSuchMethodException ex) {
                ex.printStackTrace();
                try {
                    m = fsDataOutputStreamClass.getMethod("sync");
                } catch (Exception ex1) {
                    String msg = "Neither hflush not sync were found. That seems to be " +
                            "a problem!";
                    ex1.printStackTrace();
                }
            }
        }
        return m;
    }


    /**
     * @param filePath
     * @param buffer
     */
    public static synchronized boolean appendFile(String filePath, List<String> buffer) {
        BufferedWriter writer = null;
        FSDataOutputStream output = null;
        FileSystem fs = null;
        boolean writeDone = true;
        StringBuffer stringBuffer = new StringBuffer();

        for (String msg : buffer) {
            stringBuffer.append(msg).append("\n");
        }

        try {
            fs = FileSystem.get(URI.create(hdfsUri), config());
            Path path = new Path(filePath);
            if (!fs.exists(path)) {
                output = fs.create(path);
                LOG.warn("File " + filePath + " is not exist!");
            } else {
                output = fs.append(path);
            }
            writer = new BufferedWriter(new OutputStreamWriter(output, "UTF-8"));
            writer.write(stringBuffer.toString());
            writer.flush();
        } catch (RemoteException e) {
            writeDone = false;
            //java.io.InterruptedIOException: Interruped while waiting for IO on channel java.nio.channels.SocketChannel
            closeFSDataOutputStream(output);
            closeBufferedWriter(writer);
            LOG.warn("RemoteException happened warn ", e);
        } catch (IOException e) {
            writeDone = false;
            closeFSDataOutputStream(output);
            closeBufferedWriter(writer);
            LOG.error("Create or Append HDFS files happened error ", e);
        } finally {
            closeFSDataOutputStream(output);
            closeBufferedWriter(writer);
        }
        return writeDone;
    }

    /**
     * @param filePath
     * @param fileSystem
     */
    private synchronized static void recoverLease(String filePath, FileSystem fileSystem) {
        if (filePath != null && fileSystem instanceof DistributedFileSystem) {
            try {
                LOG.info("Starting lease recovery for {}", filePath);
                ((DistributedFileSystem) fileSystem).recoverLease(new Path(filePath));
            } catch (IOException ex) {
                LOG.error("Lease recovery failed for {}", filePath, ex);
            }
        }
    }

    public static void recoverLease(String filePath) {
        FileSystem fs = null;
        try {
            fs = FileSystem.get(URI.create(hdfsUri), config());
            recoverLease(filePath, fs);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @param filePath
     * @return
     */
    public static List<String> fileReader(String filePath) {
        List<String> lines = new ArrayList<>();
        BufferedReader br = null;
        FileSystem fs = null;
        Path path = new Path(filePath);
        try {
            fs = FileSystem.get(URI.create(hdfsUri), config());

            if (fs instanceof DistributedFileSystem) {
                System.out.println("this is DistributedFileSystem");
            } else {
                System.out.println("this is not DistributedFileSystem");
            }

            if (fs.exists(path)) {
                FSDataInputStream inputStream = fs.open(path);
                br = new BufferedReader(new InputStreamReader(inputStream));

                String line = null;
                while (null != (line = br.readLine())) {
                    lines.add(line);

                    //System.out.println(line);

                }
            }
        } catch (IOException e) {
            LOG.error("Read file happened error ", e);
        } finally {
            IOUtils.closeStream(br);
        }

        return lines;
    }

    /*public static synchronized void sequenceFileWrite(String filePath, List<String> data) {
        SequenceFile.Writer writer = null;

        try {
            Text value = new Text();
            BytesWritable EMPTY_KEY = new BytesWritable();

            Path path = new Path(filePath);
            BytesWritable key = new BytesWritable();

            writer = SequenceFile.createWriter(
                    config(),
                    SequenceFile.Writer.file(path),
                    SequenceFile.Writer.keyClass(key.getClass()),
                    SequenceFile.Writer.valueClass(value.getClass()),
                    //In hadoop-2.6.0-cdh5, it can use hadoop-common-2.6.5 with appendIfExists()
                    //SequenceFile.Writer.appendIfExists(true),
                    SequenceFile.Writer.compression(SequenceFile.CompressionType.BLOCK)
            );

            int index = 0;
            for (String str : data) {
                value.set(str);
                writer.append(EMPTY_KEY, value);
                index++;
            }
            System.out.println("write size=" + index);
        } catch (RemoteException e) {
            LOG.warn("RemoteException happened warn ", e);
        } catch (IOException e) {
            LOG.error("Create or Append SequenceFile happened error ", e);
        } finally {
            IOUtils.closeStream(writer);
        }
    }*/

    public static void sequenceFileWriterSocket(String inputSeqPath, String ip) {
        SequenceFile.Reader reader = null;
        FileSystem fs = null;
        List<String> outputPaths = getFileList(inputSeqPath);
        List<String[]> datas = Lists.newArrayList();
        try {
            PrintWriter pw = getWriter(ip);
            long index = 0L;
            for (String path : outputPaths) {
                //input
                Path inPath = new Path(path);
                fs = FileSystem.get(URI.create(hdfsUri), config());
                if (fs.exists(inPath)) {
                    reader = new SequenceFile.Reader(config(), SequenceFile.Reader.file(inPath));
                    Writable inKey = (Writable) ReflectionUtils.newInstance(
                            reader.getKeyClass(), config());
                    Writable inValue = (Writable) ReflectionUtils.newInstance(
                            reader.getValueClass(), config());
                    while (reader.next(inKey, inValue)) {
                        String[] messages = inValue.toString()
                                .replaceAll("null", "0")
                                .replaceAll("\\\\N", "0").split("\t", -1);

                        if (messages.length == 22) {
                            datas.add(messages);
                            index++;
                            if (datas.size() == 500) {
                                String message = getData(datas);
                                if (!StringUtils.isEmpty(message)) {
                                    pw.write(message);
                                    pw.flush();
                                    LOG.info("put size = " + datas.size());
                                }
                                datas = Lists.newArrayList();
                            }
                        }
                    }

                }
                LOG.info("finish file = " + path);
            }
            LOG.info("all file " + inputSeqPath + " size=" + index);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeStream(reader);
            IOUtils.closeStream(fs);
        }
    }

    private static PrintWriter getWriter(String ip) throws Exception {
        final Socket s = new Socket(ip, 5240);
        final PrintWriter pw = new PrintWriter(s.getOutputStream());
        return pw;
    }

    private static String getData(List<String[]> datas) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        StringBuilder sb = new StringBuilder();
        for (String[] msg : datas) {
            String[] newMsg = new String[24];
            newMsg[0] = "pt_consume_test";
            newMsg[1] = simpleDateFormat.format(new Date());
            for (int i = 2; i < msg.length + 2; i++) {
                newMsg[i] = msg[i - 2];
            }
            String data = String.join("|", newMsg);
            sb.append(data).append("\n");
        }
        return sb.toString();
    }

    public static void sequenceFileWritePhoenix(String inputSeqPath, String url, Properties props, String insertSql) {
        SequenceFile.Reader reader = null;
        FileSystem fs = null;
        List<String> outputPaths = getFileList(inputSeqPath);
        PreparedStatement pst = null;
        List<String[]> datas = Lists.newArrayList();
        try {
            long index = 0L;
            for (String path : outputPaths) {
                //input
                Path inPath = new Path(path);
                fs = FileSystem.get(URI.create(hdfsUri), config());
                if (fs.exists(inPath)) {
                    reader = new SequenceFile.Reader(config(), SequenceFile.Reader.file(inPath));
                    Writable inKey = (Writable) ReflectionUtils.newInstance(
                            reader.getKeyClass(), config());
                    Writable inValue = (Writable) ReflectionUtils.newInstance(
                            reader.getValueClass(), config());
                    while (reader.next(inKey, inValue)) {
                        String[] messages = inValue.toString().replaceAll("\\\\N", "0").split("\t", -1);
                        if (messages.length == 15) {
                            datas.add(messages);
                            index++;
                            if (datas.size() == 10) {
                                insert(url, props, insertSql, datas, pst);
                                datas = Lists.newArrayList();
                            }
                        }
                    }
                }
                LOG.info("finish file = " + path);
            }
            LOG.info("all file " + inputSeqPath + " size=" + index);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeStream(reader);
            IOUtils.closeStream(fs);
        }
    }

    /*static class Task implements Runnable {

        private String url;
        private Properties props;
        private String insertSql;
        private List<String[]> datas;
        private PreparedStatement pst;

        public Task(String url, Properties props, String insertSql, List<String[]> datas, PreparedStatement pst) {
            this.url = url;
            this.props = props;
            this.insertSql = insertSql;
            this.datas = datas;
            this.pst = pst;
        }

        @Override
        public void run() {
            try {
                insert(url, props, insertSql, datas, pst);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }*/

    private static void insert(String url, Properties props, String insertSql, List<String[]> datas, PreparedStatement pst) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, props)) {
            pst = connection.prepareStatement(insertSql);
            connection.setAutoCommit(false);
            for (String[] messages : datas) {
                try {
                   /* pst.setInt(1, Integer.parseInt(StringUtils.isEmpty(messages[1]) ? "0" : messages[1]));//ACCOUNT_ID
                    pst.setString(2, messages[0]);//DATA_DATE
                    pst.setString(3, messages[2]);//MOBILE
                    pst.setInt(4, Integer.parseInt(StringUtils.isEmpty(messages[3]) ? "0" : messages[3]));//VIP_LEVEL
                    pst.setString(5, messages[4]);//SNDA_ID
                    pst.setString(6, messages[5]);//PT_ID
                    pst.setDouble(7, Double.parseDouble(StringUtils.isEmpty(messages[6]) ? "0.0" : messages[6]));//CON_MOBILE
                    pst.setInt(8, Integer.parseInt(StringUtils.isEmpty(messages[7]) ? "0" : messages[7]));//GAME_ID
                    pst.setString(9, messages[8]);//LAST_LOGIN_TIME_PT
                    pst.setDouble(10, Double.parseDouble(StringUtils.isEmpty(messages[9]) ? "0.0" : messages[9]));//LAST_CONSUME_6M_PT
                    pst.setInt(11, Integer.parseInt(StringUtils.isEmpty(messages[10]) ? "0" : messages[10]));//LAST_GAME_ID_PT
                    pst.setInt(12, Integer.parseInt(StringUtils.isEmpty(messages[11]) ? "0" : messages[11]));//VIRTUAL_FLAG
                    pst.setString(13, messages[12]);//LAST_CONSUME_TIME_MOBILE
                    pst.setInt(14, Integer.parseInt(StringUtils.isEmpty(messages[13]) ? "0" : messages[13]));//LAST_CONSUME_GAME_MOBILE
                    pst.setInt(15, Integer.parseInt(StringUtils.isEmpty(messages[14]) ? "0" : messages[14]));//STUDIO_ID
                    pst.setDouble(16, Double.parseDouble(StringUtils.isEmpty(messages[15]) ? "0.0" : messages[15]));//CONSUME_180D
                    pst.setDouble(17, Double.parseDouble(StringUtils.isEmpty(messages[16]) ? "0.0" : messages[16]));//CONSUME_GAME_180D
                    pst.setDouble(18, Double.parseDouble(StringUtils.isEmpty(messages[17]) ? "0.0" : messages[17]));//G_SCORE
                    pst.setDouble(19, Double.parseDouble(StringUtils.isEmpty(messages[18]) ? "0.0" : messages[18]));//LAST_CON180D_GAME_AMOUNT_PT
                    pst.setDouble(20, Double.parseDouble(StringUtils.isEmpty(messages[19]) ? "0.0" : messages[19]));//LAST_CON180D_GAME_AMOUNT
                    pst.setString(21, messages[20]);//VIP_LEVEL_NAME
                    pst.addBatch();*/

                    pst.setString(1, UUID.randomUUID().toString());
                    pst.setString(2, messages[0]);
                    pst.setString(3, messages[1]);
                    pst.setInt(4, Integer.parseInt(messages[2]));
                    pst.setInt(5, Integer.parseInt(messages[3]));
                    pst.setString(6, messages[4]);
                    pst.setString(7, messages[5]);
                    pst.setString(8, messages[6]);
                    pst.setString(9, messages[7]);
                    pst.setInt(10, Integer.parseInt(messages[8]));
                    pst.setString(11, messages[9]);
                    pst.setString(12, messages[10]);
                    pst.setString(13, messages[11]);
                    pst.setString(14, messages[12]);
                    pst.setString(15, messages[13]);
                    pst.setString(16, messages[14]);
                    pst.addBatch();
                } catch (Exception e) {
                    LOG.info("messages = " + StringUtils.join(messages, ","));
                }
            }
            pst.executeBatch();
            connection.commit();
        } finally {
            pst.close();
            LOG.info("Insert data size = " + datas.size());
        }
    }

    public static synchronized void sequenceFileWriteIndex(String txtFilePath, String sequenceFilePath) {
        SequenceFile.Writer writer = null;
        BufferedReader br = null;
        FileSystem fs = null;

        try {
            // sequenceFile
            Path sequencePath = new Path(sequenceFilePath);
            Text values = new Text();
            Text key = new Text();
            writer = SequenceFile.createWriter(
                    config(),
                    SequenceFile.Writer.file(sequencePath),
                    SequenceFile.Writer.keyClass(key.getClass()),
                    SequenceFile.Writer.valueClass(values.getClass()),
                    //In hadoop-2.6.0-cdh5, it can use hadoop-common-2.6.5 with appendIfExists()
                    //SequenceFile.Writer.appendIfExists(true),
                    SequenceFile.Writer.compression(SequenceFile.CompressionType.BLOCK)
            );

            // txt
            Path txtPath = new Path(txtFilePath);
            fs = FileSystem.get(URI.create(hdfsUri), config());
            if (fs.exists(txtPath)) {
                FSDataInputStream inputStream = fs.open(txtPath);
                br = new BufferedReader(new InputStreamReader(inputStream));

                String line;
                long index = 0L;
                while (null != (line = br.readLine())) {
                    Text msgValueText = new Text();
                    Text msgKeyText = new Text();
                    String msgKey;
                    try {
                        msgKey = String.valueOf(index);
                    } catch (Exception e) {
                        msgKey = String.valueOf(System.currentTimeMillis());
                    }
                    String msgValue = line;

                    //String msgKey = String.valueOf(System.currentTimeMillis()) + index;

                    msgKeyText.set(msgKey);
                    msgValueText.set(msgValue);
                    writer.append(msgKeyText, msgValueText);
                    index++;
                }
                LOG.info("file " + txtPath + " size=" + index);
            }
        } catch (RemoteException e) {
            LOG.warn("RemoteException happened warn. File= " + sequenceFilePath, e);
        } catch (IOException e) {
            LOG.error("Create or Append SequenceFile happened error. File= " + sequenceFilePath, e);
        } finally {
            IOUtils.closeStream(writer);
            IOUtils.closeStream(br);
            IOUtils.closeStream(fs);
        }
    }

    public static List<String> getFileList(String dataPath) {
        List<String> fileList = new ArrayList<String>();
        try {
            Path path = new Path(dataPath);
            FileSystem fs = FileSystem.get(URI.create(hdfsUri), config());
            FileStatus[] fileStatusArray = fs.globStatus(path);
            if (fileStatusArray != null) {
                for (FileStatus fileStatus : fileStatusArray) {
                    if (fs.isFile(fileStatus.getPath())) {
                        String fullPath = fileStatus.getPath().toString();
                        fileList.add(fullPath);
                    } else if (fs.isDirectory(fileStatus.getPath())) {
                        for (FileStatus fileStatus2 : fs.listStatus(fileStatus
                                .getPath())) {
                            if (fs.isFile(fileStatus2.getPath())) {
                                String fullPath = fileStatus2.getPath()
                                        .toString();
                                fileList.add(fullPath);
                            } else {
                                throw new Exception("file path error:");
                            }
                        }
                    }
                }
            } else {
            }
            return fileList;
        } catch (Exception e) {
            LOG.error("Get file list error." + dataPath, e);
        }
        return Lists.newArrayList();
    }


    public static synchronized void sequenceFileWriteTosql(String inputSeqPath, String outputSeqPath, String tableName) {
        SequenceFile.Writer writer = null;
        SequenceFile.Reader reader = null;
        BufferedReader br = null;
        FileSystem fs = null;
        try {
            // output
            Path sequencePath = new Path(outputSeqPath);
            Text value = new Text();
            Text key = new Text();
            writer = SequenceFile.createWriter(
                    config(),
                    SequenceFile.Writer.file(sequencePath),
                    SequenceFile.Writer.keyClass(key.getClass()),
                    SequenceFile.Writer.valueClass(value.getClass()),
                    //In hadoop-2.6.0-cdh5, it can use hadoop-common-2.6.5 with appendIfExists()
                    //SequenceFile.Writer.appendIfExists(true),
                    SequenceFile.Writer.compression(SequenceFile.CompressionType.BLOCK)
            );

            List<String> outputPaths = getFileList(inputSeqPath);
            long index = 0L;
            for (String path : outputPaths) {
                //input

                if (!path.contains(tableName + "_00") && !path.contains(tableName + "_01")
                        && !path.contains(tableName + "_02") && !path.contains(tableName + "_03")
                        && !path.contains(tableName + "_04") && !path.contains(tableName + "_05")
                        && !path.contains(tableName + "_16") && !path.contains(tableName + "_17")
                        && !path.contains(tableName + "_18") && !path.contains(tableName + "_19")
                        && !path.contains(tableName + "_20") && !path.contains(tableName + "_21")
                        && !path.contains(tableName + "_22") && !path.contains(tableName + "_23")) {

                    Path inPath = new Path(path);
                    fs = FileSystem.get(URI.create(hdfsUri), config());
                    if (fs.exists(inPath)) {
                        reader = new SequenceFile.Reader(config(), SequenceFile.Reader.file(inPath));
                        Writable inKey = (Writable) ReflectionUtils.newInstance(
                                reader.getKeyClass(), config());
                        Writable inValue = (Writable) ReflectionUtils.newInstance(
                                reader.getValueClass(), config());
                        while (reader.next(inKey, inValue)) {
                            Text msgKey = new Text();
                            String[] msgs = inValue.toString().split("\\t", -1);
                            if (!msgs[1].equalsIgnoreCase("791000352")) {
                                msgKey.set(inKey.toString());
                                value.set(inValue.toString());
                                writer.append(msgKey, value);
                                index++;
                            }
                        }
                    }
                    LOG.info("finish file = " + path);
                    LOG.info("all file " + inputSeqPath + " size=" + index);
                }
            }
        } catch (RemoteException e) {
            LOG.warn("RemoteException happened warn. File= " + outputSeqPath, e);
        } catch (IOException e) {
            LOG.error("Create or Append SequenceFile happened error. File= " + outputSeqPath, e);
        } finally {
            IOUtils.closeStream(reader);
            IOUtils.closeStream(writer);
            IOUtils.closeStream(br);
            IOUtils.closeStream(fs);
        }
    }

    public static synchronized void sequenceFileWrite(String txtFilePath, String sequenceFilePath) throws Exception {
        SequenceFile.Writer writer = null;
        BufferedReader br = null;
        FileSystem fs = null;
        try {
            // sequenceFile
            Path sequencePath = new Path(sequenceFilePath);
            Text values = new Text();
            Text key = new Text();
            writer = SequenceFile.createWriter(
                    config(),
                    SequenceFile.Writer.file(sequencePath),
                    SequenceFile.Writer.keyClass(key.getClass()),
                    SequenceFile.Writer.valueClass(values.getClass()),
                    //In hadoop-2.6.0-cdh5, it can use hadoop-common-2.6.5 with appendIfExists()
                    //SequenceFile.Writer.appendIfExists(true),
                    SequenceFile.Writer.compression(SequenceFile.CompressionType.BLOCK)
            );

            // txt
            Path txtPath = new Path(txtFilePath);
            fs = FileSystem.get(URI.create(hdfsUri), config());
            if (fs.exists(txtPath)) {
                FSDataInputStream inputStream = fs.open(txtPath);
                br = new BufferedReader(new InputStreamReader(inputStream));

                String line;
                long index = 0L;
                while (null != (line = br.readLine())) {
                    Text msgValueText = new Text();
                    Text msgKeyText = new Text();
                    String[] message = line.split("\\|", -1);

                    StringBuffer stringBuffer = new StringBuffer();
                    for (int i = 1; i < message.length; i++) {
                        stringBuffer.append(message[i]).append("\t");
                    }
                    stringBuffer.deleteCharAt(stringBuffer.length() - 1);
                    msgKeyText.set(UUID.randomUUID().toString());
                    msgValueText.set(stringBuffer.toString());
                    writer.append(msgKeyText, msgValueText);
                    index++;
                }
                LOG.info("file " + txtPath + " size=" + index);
            }
        } catch (RemoteException e) {
            LOG.warn("RemoteException happened warn. File= " + sequenceFilePath, e);
        } catch (IOException e) {
            LOG.error("Create or Append SequenceFile happened error. File= " + sequenceFilePath, e);
        } finally {
            IOUtils.closeStream(writer);
            IOUtils.closeStream(br);
            IOUtils.closeStream(fs);
        }
    }

    public static synchronized void sequenceFileReadAndWrite(String inputPath, String outputPath) {
        SequenceFile.Writer writer = null;
        SequenceFile.Reader reader = null;
        BufferedReader br = null;
        FileSystem fs = null;

        List<String> areaId = new ArrayList<>();

        for (int i = 0; i < 41; i++) {
            areaId.add((i + 1) + "");
        }

        String count = "gm01\n" +
                "gm02\n" +
                "gm03\n" +
                "gm04\n" +
                "gm05\n" +
                "gm06\n" +
                "gm07\n" +
                "gm08\n" +
                "gm09\n" +
                "dkm01\n" +
                "dkm02\n" +
                "dkm03\n" +
                "dkm04\n" +
                "dkm05\n" +
                "dkm06\n" +
                "dkm07\n" +
                "dkm08\n" +
                "dkm09\n" +
                "ch01\n" +
                "ch02\n" +
                "ch03\n" +
                "ch04\n" +
                "ch05\n" +
                "ch06\n" +
                "ch07\n" +
                "ch08\n" +
                "ch09\n" +
                "ms01\n" +
                "ms02\n" +
                "ms03\n" +
                "ms04\n" +
                "ms05\n" +
                "ms06\n" +
                "ms07\n" +
                "ms08\n" +
                "ms09\n" +
                "cs01\n" +
                "cs02\n" +
                "cs03\n" +
                "cs04\n" +
                "cs05\n" +
                "cs06\n" +
                "cs07\n" +
                "cs08\n" +
                "cs09\n" +
                "yw01\n" +
                "yw02\n" +
                "yw03\n" +
                "yw04\n" +
                "yw05\n" +
                "yw06\n" +
                "yw07\n" +
                "yw08\n" +
                "yw09\n" +
                "test01\n" +
                "test02\n" +
                "test03\n" +
                "test04\n" +
                "cx01\n" +
                "cx02\n" +
                "cx03\n" +
                "cx04\n" +
                "cx05\n" +
                "cx06\n" +
                "cx07\n" +
                "cx08\n" +
                "cx09";

        String[] counts = count.split("\\n", -1);

        List<String> countList = Arrays.asList(counts);


        try {
            // output
            Path sequencePath = new Path(outputPath);
            Text value = new Text();
            Text key = new Text();
            writer = SequenceFile.createWriter(
                    config(),
                    SequenceFile.Writer.file(sequencePath),
                    SequenceFile.Writer.keyClass(key.getClass()),
                    SequenceFile.Writer.valueClass(value.getClass()),
                    //In hadoop-2.6.0-cdh5, it can use hadoop-common-2.6.5 with appendIfExists()
                    //SequenceFile.Writer.appendIfExists(true),
                    SequenceFile.Writer.compression(SequenceFile.CompressionType.BLOCK)
            );

            //input

            Path inPath = new Path(inputPath);

            fs = FileSystem.get(URI.create(hdfsUri), config());
            if (fs.exists(inPath)) {
                reader = new SequenceFile.Reader(config(), SequenceFile.Reader.file(inPath));
                Writable inKey = (Writable) ReflectionUtils.newInstance(
                        reader.getKeyClass(), config());
                Writable inValue = (Writable) ReflectionUtils.newInstance(
                        reader.getValueClass(), config());
                long index = 0L;
                while (reader.next(inKey, inValue)) {
                    Text msgKey = new Text();
                    String[] msgs = inValue.toString().split("\\t", -1);

                    if (areaId.contains(msgs[1]) &&
                            msgs[2].equalsIgnoreCase("1") &&
                            msgs[4].equalsIgnoreCase("")) {
                        msgs[4] = "9187";
                    }

                    msgKey.set(inKey.toString());
                    value.set(StringUtils.join(msgs, "\t"));
                    writer.append(msgKey, value);
                    index++;
                }
                LOG.info("file " + inPath + " size=" + index);
            }
        } catch (RemoteException e) {
            LOG.warn("RemoteException happened warn. File= " + outputPath, e);
        } catch (IOException e) {
            LOG.error("Create or Append SequenceFile happened error. File= " + outputPath, e);
        } finally {
            IOUtils.closeStream(reader);
            IOUtils.closeStream(writer);
            IOUtils.closeStream(br);
            IOUtils.closeStream(fs);
        }
    }

    /**
     * @param filePath
     * @return
     */
    public static Map<String, String> sequenceFileReader(String filePath) {
        Map<String, String> messages = new HashMap<>();

        Path path = new Path(filePath);
        SequenceFile.Reader reader = null;
        FileSystem fs = null;
        try {
            fs = FileSystem.get(URI.create(hdfsUri), config());
            if (fs.exists(path)) {
                reader = new SequenceFile.Reader(config(), SequenceFile.Reader.file(path));
                Text key = (Text) ReflectionUtils.newInstance(
                        reader.getKeyClass(), config());
                Text value = (Text) ReflectionUtils.newInstance(
                        reader.getValueClass(), config());
                while (reader.next(key, value)) {
                    String msgKey = key.toString();
                    String msgValue = value.toString();

                    if (msgValue.contains("2017-12-02 01:02:14") && msgValue.contains("2596055061") && msgValue.contains("791000276PP016171202010211000001")) {
                        LOG.info("key = " + msgKey);
                        LOG.info("value = " + msgValue);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Read SequenceFile happened error ", e);
        } finally {
            IOUtils.closeStream(reader);
        }
        return messages;
    }

    /**
     * @param filePath
     * @return
     */
/*    public static Map<String, String> sequenceFileReader2Json(String filePath, String outputFile, String topic) throws Exception {
        Map<String, String> messages = new HashMap<>();

        Schema schema = SchemaHelper.getSchema(topic);

        LOG.info("schema = " + schema);

        FileWriter writer = new FileWriter(outputFile, true);

        Path path = new Path(filePath);
        SequenceFile.Reader reader = null;
        FileSystem fs = null;

        int dataSize = 0;
        try {
            fs = FileSystem.get(URI.create(hdfsUri), config());
            if (fs.exists(path)) {
                reader = new SequenceFile.Reader(config(), SequenceFile.Reader.file(path));
                BytesWritable key = (BytesWritable) ReflectionUtils.newInstance(
                        reader.getKeyClass(), config());
                Text value = (Text) ReflectionUtils.newInstance(
                        reader.getValueClass(), config());

                while (reader.next(key, value)) {
                    JsonObject jsonObject = new JsonObject();
                    try {
                        String msgValue = value.toString();

                        String[] doneMsg = msgValue.split("\t", -1);

                        int index = 0;
                        for (Schema.Field field : schema.getFields()) {
                            jsonObject.addProperty(field.name(), doneMsg[index]);
                            index++;
                        }
                        method2(writer, jsonObject.toString());
                        if (dataSize % 1000 == 0) {
                            System.out.println(jsonObject.toString());
                        }
                        dataSize++;
                    } catch (Exception e) {
                        LOG.error("Format data error! Data = " + value + ",json = " + jsonObject.toString(), e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Read SequenceFile happened error ", e);
        } finally {
            IOUtils.closeStream(reader);
            writer.close();
            LOG.info("Data size = " + dataSize);
        }
        return messages;
    }*/

    /**
     * 追加文件：使用FileWriter
     *
     * @param content
     */
    public static void method2(FileWriter writer, String content) {
        try {
            writer.write(content + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @param filePath
     */
    public static synchronized void deleteFile(String filePath) {
        FileSystem fs = null;
        try {
            fs = FileSystem.get(URI.create(hdfsUri), config());
            fs.delete(new Path(filePath), true);
        } catch (Exception e) {
            closeFileSystem(fs);
            LOG.error(String.format("Delete file %s error ", filePath), e);
        }
    }

    /**
     * @param txtPath
     * @param sequencePath
     */
    public static void txtFile2SequenceFile(String txtPath, String sequencePath) {
        sequenceFileWriteIndex(txtPath, sequencePath);
        //sequenceFileWrite(txtPath, sequencePath);
        //sequenceFileReader(sequencePath);
    }

    public static void sequenceFile2SequenceFile(String inputPath, String outputPath) {
        sequenceFileReadAndWrite(inputPath, outputPath);
    }

    /**
     * @param filePath
     * @return
     */
    private static void createFile(String filePath) {
        Path path = new Path(filePath);
        FileSystem fs = getNewFs();
        try {
            if (!fs.exists(path)) {
                fs.create(path).close();
            }
        } catch (IOException e) {
            closeFileSystem(fs);
            LOG.error("Create HDFS files happened error : ", e);
        }
    }


    public static void deleteFiles(String fileName) throws Exception {
        FileSystem hdfs = FileSystem.get(config());
        Path path = new Path(fileName);
        boolean isExists = hdfs.exists(path);
        if (isExists) {
            boolean isDel = hdfs.delete(path, true);
            LOG.info(fileName + "  delete? \t" + isDel);
        } else {
            LOG.error(fileName + "  exist? \t" + isExists);
        }
    }


    /**
     * @param fs
     */
    private static void closeFileSystem(FileSystem fs) {
        try {
            if (fs != null) {
                fs.close();
                fs = null;
                LOG.info("FileSystem closed!!");
            }
        } catch (IOException e) {
            LOG.error("Close HDFS FileSystem happened error ", e);
        }
    }

    /**
     * @return
     */
    private static FileSystem getNewFs() {
        FileSystem fs = null;
        try {
            fs = FileSystem.get(URI.create(hdfsUri), config());
        } catch (IOException e) {
            closeFileSystem(fs);
            LOG.error("Get NewFs happened error ", e);
        }
        return fs;
    }

    /**
     * 关闭 BufferedWriter
     *
     * @param br
     */
    private static void closeBufferedWriter(BufferedWriter br) {
        try {
            if (br != null) {
                br.close();
            }
        } catch (IOException e) {
            LOG.error("Close BufferedWriter happened error" + e);
        }
    }


    private static void closeFSDataOutputStream(FSDataOutputStream fsos) {
        try {
            if (fsos != null) {
                fsos.close();
            }
        } catch (IOException e) {
            LOG.error("Close FSDataOutputStream happened error" + e);
        }
    }

    public static void renameFile(String oldFile, String newFile) {
        try {
            FileSystem hdfs = FileSystem.get(URI.create(hdfsUri), config());
            Path oldPath = new Path(oldFile);
            Path newPath = new Path(newFile);
            Path filePath = new Path(newFile.substring(0, newFile.lastIndexOf("/")));
            if (!hdfs.exists(filePath)) {
                hdfs.mkdirs(filePath);
                LOG.info("Create  path = " + filePath);
            }
            boolean isRename = hdfs.rename(oldPath, newPath);
            if (isRename) {
                LOG.info("oldFile " + oldPath + " to newFile " + newFile + " Success!");
            } else {
                LOG.error("oldFile " + oldPath + " to newFile " + newFile + " Error!");
            }
        } catch (Exception e) {
            LOG.error("Rename file = " + oldFile + " error!", e);
        }
    }
}
