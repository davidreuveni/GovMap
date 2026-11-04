package il.david.govmap.dark;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Metadata-only, bounded directory inspection. Never follows descendant symlinks. */
final class DeveloperStorage {
    static final class Totals {
        long files,bytes;boolean present,partial;int visited;
        String text(){return !present?"not present":DeveloperFacts.bytes(bytes)+", "+files+" files"+(partial?" (partial scan)":"");}
    }
    static Totals scan(File root){return scan(root,10000,12);}
    static Totals scan(File root,int limit,int depth){
        Totals result=new Totals();if(!root.exists())return result;result.present=true;
        if(limit<=0||depth<=0){result.partial=true;return result;}
        try{
            Files.walkFileTree(root.getCanonicalFile().toPath(),Collections.emptySet(),depth,new SimpleFileVisitor<Path>(){
                private FileVisitResult visit(){if(++result.visited>limit){result.partial=true;return FileVisitResult.TERMINATE;}return FileVisitResult.CONTINUE;}
                @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs){return visit();}
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs){
                    FileVisitResult next=visit();if(next==FileVisitResult.TERMINATE)return next;
                    if(attrs.isRegularFile()){result.files++;result.bytes+=Math.max(0,attrs.size());}
                    else if(attrs.isDirectory())result.partial=true;
                    return next;
                }
                @Override public FileVisitResult visitFileFailed(Path file,IOException failure){result.partial=true;return visit();}
            });
        }catch(IOException|SecurityException e){result.partial=true;}return result;
    }
    static Totals bodies(File folder){
        Totals result=new Totals();if(!folder.isDirectory())return result;result.present=true;
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(folder.toPath(),"*.body")){
            for(Path p:paths){if(++result.visited>1000){result.partial=true;break;}
                if(p.getFileName().toString().matches("[0-9a-f]{64}\\.body")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)){result.files++;result.bytes+=Files.size(p);}}
        }catch(IOException|SecurityException e){result.partial=true;}return result;
    }
}
