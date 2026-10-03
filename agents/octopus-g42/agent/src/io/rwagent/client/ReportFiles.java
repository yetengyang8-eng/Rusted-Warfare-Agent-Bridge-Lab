package io.rwagent.client;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** A .jsonl name denotes a closed, flushed report; interrupted runs retain .jsonl.partial. */
final class ReportFiles {
    private ReportFiles(){}
    static FileOutputStream open(File target)throws IOException {
        File partial=new File(target.getPath()+".partial");
        if(!partial.createNewFile())throw new IOException("Report already exists: "+partial);
        return new SnapshotOutput(partial);
    }
    static void finish(BufferedWriter writer,FileOutputStream stream,File target)throws IOException {
        writer.flush();stream.getFD().sync();
        if(!(stream instanceof SnapshotOutput))throw new IOException("Report stream must retain its commit snapshot");
        SnapshotOutput snapshot=(SnapshotOutput)stream;
        writer.close();
        // Write to a NEW path after completion. A reader of the live append-only journal
        // must never cause the committed report to inherit a stale filesystem snapshot.
        Path commit=target.toPath().resolveSibling(target.getName()+".commit-"+java.util.UUID.randomUUID());
        try(FileOutputStream saved=new FileOutputStream(commit.toFile())){
            snapshot.copyTo(saved);saved.flush();saved.getFD().sync();
        }
        try {Files.move(commit,target.toPath(),StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e){Files.move(commit,target.toPath());}
        Files.deleteIfExists(new File(target.getPath()+".partial").toPath());
        snapshot.cleanSpool();
    }
    private static final class SnapshotOutput extends FileOutputStream {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private static final int MEMORY_LIMIT=8*1024*1024;
        private final long limit=Math.max(64,Math.min(4096,Integer.getInteger("rwagent.reportLimitMiB",1024)))*1024L*1024L;
        private final Path spool;
        private final List<Path> chunks=new ArrayList<Path>();
        private final List<byte[]> hashes=new ArrayList<byte[]>();
        private long total;
        SnapshotOutput(File path)throws IOException{super(path);spool=Paths.get(path.getPath()+".spool");}
        @Override public synchronized void write(byte[] b,int off,int len)throws IOException{
            if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();
            if(len>limit-total)throw new IOException("Report exceeds configured disk budget: "+limit+" bytes");
            super.write(b,off,len);total+=len;
            while(len>0){
                int n=Math.min(len,MEMORY_LIMIT-bytes.size());bytes.write(b,off,n);off+=n;len-=n;
                if(bytes.size()==MEMORY_LIMIT)spill();
            }
        }
        @Override public void write(byte[] b)throws IOException{write(b,0,b.length);}
        @Override public synchronized void write(int value)throws IOException{
            write(new byte[]{(byte)value},0,1);
        }
        private static MessageDigest digest()throws IOException{
            try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException(e);}
        }
        private void spill()throws IOException{
            if(chunks.isEmpty())Files.createDirectory(spool);
            Path part=spool.resolve(String.format(Locale.ROOT,"%06d.snapshot",chunks.size()+1));
            MessageDigest hash=digest();
            try(FileOutputStream saved=new FileOutputStream(part.toFile())){
                DigestOutputStream hashing=new DigestOutputStream(saved,hash);bytes.writeTo(hashing);hashing.flush();saved.getFD().sync();
            }
            chunks.add(part);hashes.add(hash.digest());bytes.reset();
        }
        synchronized void copyTo(OutputStream out)throws IOException{
            byte[] buffer=new byte[65536];long copied=0;
            for(int index=0;index<chunks.size();index++){
                MessageDigest hash=digest();
                try(InputStream input=Files.newInputStream(chunks.get(index))){
                    for(int n;(n=input.read(buffer))!=-1;){hash.update(buffer,0,n);out.write(buffer,0,n);copied+=n;}
                }
                if(!MessageDigest.isEqual(hashes.get(index),hash.digest()))throw new IOException("Report snapshot integrity failure");
            }
            bytes.writeTo(out);copied+=bytes.size();if(copied!=total)throw new IOException("Report snapshot length mismatch");
        }
        void cleanSpool()throws IOException{
            for(Path part:chunks)Files.deleteIfExists(part);
            Files.deleteIfExists(spool);
        }
    }
}
