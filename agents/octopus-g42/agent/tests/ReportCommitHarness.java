package io.rwagent.client;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Locale;
import java.security.*;
public final class ReportCommitHarness {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory(Paths.get("."),"rwreportcommit-");
        File target=root.resolve("test.jsonl").toFile();
        FileOutputStream output=ReportFiles.open(target);
        BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));
        String first="{\"event\":\"health\",\"message\":\"中文\"}\n",last="{\"event\":\"summary\",\"outcome\":\"PASS\"}\n";
        writer.write(first);writer.flush();
        Path partial=root.resolve("test.jsonl.partial");
        // Renaming a file that still has an open handle is POSIX-only: Windows refuses it with a
        // sharing violation, so this one simulation is skipped there instead of failing the suite.
        // Everything below still exercises the real commit path on both platforms.
        boolean posix=!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");
        if(posix){
            // Simulate a filesystem reader materializing an older journal snapshot at the path
            // while the writer's open descriptor continues on the original inode.
            Files.move(partial,root.resolve("displaced-journal"));
            Files.write(partial,first.getBytes(StandardCharsets.UTF_8));
        }else{
            System.out.println("REPORT_COMMIT_TEST_SKIP posix-only open-handle rename simulation on "
                    +System.getProperty("os.name")+"; remaining commit checks still run");
        }
        writer.write(last);ReportFiles.finish(writer,output,target);
        if(!Arrays.equals(Files.readAllBytes(target.toPath()),(first+last).getBytes(StandardCharsets.UTF_8)))throw new AssertionError("Committed snapshot lost appended events");
        if(Files.exists(partial))throw new AssertionError("partial not cleaned");
        if(posix)Files.delete(root.resolve("displaced-journal"));
        Files.delete(target.toPath());
        // Regression for the real >64 MiB failure. A bounded heap cannot retain this whole report.
        File large=root.resolve("large.jsonl").toFile();FileOutputStream largeOut=ReportFiles.open(large);
        BufferedWriter largeWriter=new BufferedWriter(new OutputStreamWriter(largeOut,StandardCharsets.UTF_8));
        byte[] block=new byte[1024*1024];Arrays.fill(block,(byte)'a');block[block.length-1]='\n';
        MessageDigest expected=MessageDigest.getInstance("SHA-256");
        for(int i=0;i<72;i++){largeOut.write(block);expected.update(block);}
        ReportFiles.finish(largeWriter,largeOut,large);
        MessageDigest actual=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(large.toPath())){for(int n;(n=in.read(block))>=0;)actual.update(block,0,n);}
        if(Files.size(large.toPath())!=72L*1024*1024||!Arrays.equals(expected.digest(),actual.digest()))throw new AssertionError("Large report lost bytes");
        if(Files.exists(Paths.get(large.getPath()+".partial.spool")))throw new AssertionError("committed spool not cleaned");
        Files.delete(large.toPath());Files.delete(root);
        System.out.println("REPORT_COMMIT_TEST_OK 72 MiB bounded-memory commit and stale journal replacement retained complete UTF-8 content"
                +(posix?"":" (displacement simulation skipped)"));
    }
}
