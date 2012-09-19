// Copyright (c) 2012 Aalto University
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
// IN THE SOFTWARE.

package fi.aalto.seqpig;

import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.EvalFunc;
import org.apache.pig.PigWarning;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.util.WrappedIOException;

import it.crs4.seal.common.AlignOp;
import it.crs4.seal.common.MdOp;
import it.crs4.seal.common.WritableMapping;
import it.crs4.seal.common.AbstractTaggedMapping;
import it.crs4.seal.common.FormatException;

import java.io.IOException;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;

/* UDF ReadPileup 
   
 * takes a single read (created via input format from SAMRecord) and produces pileup data
    for each reference position
*/

public class ReadPileup extends EvalFunc<DataBag>
{
    private WritableMapping mapping = new WritableMapping();
    private ArrayList<Boolean> matches = new ArrayList();
    private List<AlignOp> alignment;
    private List<MdOp> mdOps;
    private String sequence;
    private String mapping_quality;

    private TupleFactory mTupleFactory = TupleFactory.getInstance();
    private BagFactory mBagFactory = BagFactory.getInstance();

    private List<Tuple> deletionTuples = new ArrayList();

    // tuple input format:
    //   sequence
    //   flag
    //   chr
    //   position
    //   cigar
    //   base qualities
    //   MD tag
    //   mapping quality

    // tuple output format:
    //   chr
    //   position
    //   ref base (if known, else null value)
    //   pileup string (according to samtools)
    //   base quality/ies (if applicable else null)
   
    // WARNINGS:
    // we use the folling Pig UDF warnings:
    // 	PigWarning.UDF_WARNING_2 :	problems with parsing CIGAR string
    // 	PigWarning.UDF_WARNING_3 :	problem with parsing MD tag
    // 	PigWarning.UDF_WARNING_4 :	other problem

    // compares the length indicated by CIGAR and MD strings and CIGAR and read sequence length; returns true if these match and
    // false otherwise
    private boolean compareLengthCigarMd() {
	int insertclip_length = 0, match_length = 0, md_length = 0;

	for (AlignOp alignOp: alignment) {
                if (alignOp.getType() == AlignOp.Type.Match)
			match_length += alignOp.getLen();
		else if(alignOp.getType() == AlignOp.Type.Insert || alignOp.getType() == AlignOp.Type.SoftClip)
			insertclip_length += alignOp.getLen();
   	}

	for (MdOp mdOp: mdOps) {
		if (mdOp.getType() == MdOp.Type.Match || mdOp.getType() == MdOp.Type.Mismatch) {
			md_length += mdOp.getLen();
		}

	}

	return ((match_length == md_length) && (match_length + insertclip_length == sequence.length()));
    }

    @Override 
    public DataBag exec(Tuple input) throws IOException, org.apache.pig.backend.executionengine.ExecException {
	if (input == null || input.size() == 0)
	    return null;
	try {
	    // first load the mapping and do some error checks
	    
	    String basequal = (String)input.get(5);

	    mapping.clear();

            sequence = (String)input.get(0);
            mapping.setSequence(sequence);
            mapping.setFlag(((Integer)input.get(1)).intValue());

	    if (!mapping.isMapped() || input.get(2)==null) {
	    	warn("encountered unmapped read or empty chromosome/contig id!", PigWarning.UDF_WARNING_4);
		return null;
            }

	    mapping.setContig((String)input.get(2));
            mapping.set5Position(((Integer)input.get(3)).intValue());

	   // note: the following tries to mimic samtools mpileup
	   if( //((Integer)input.get(7)).intValue() == 255 ||
		((Integer)input.get(7)).intValue() < 0 || ((Integer)input.get(7)).intValue() >= 93) {// mapping quality not available or otherwise weird
		mapping_quality = "~";
		/*if(((Integer)input.get(7)).intValue() == 255)
			basequal = "2" + basequal.substring(1);*/		
	   } else
            	mapping_quality = new String(new byte[]{(byte)(((Integer)input.get(7)).intValue()+33)}, "US-ASCII");

            try {
                mapping.setAlignment(AlignOp.scanCigar((String)input.get(4)));
                alignment = mapping.getAlignment();
            } catch(FormatException e) {
                warn("errors parsing CIGAR string (input: " + (String)input.get(4) + "), silently IGNORING read, exception: "+e.toString(), PigWarning.UDF_WARNING_2);
		return null;
            }

            try {
            	mapping.setTag("MD", AbstractTaggedMapping.TagDataType.String, ((String)input.get(6)).toUpperCase());
           	mdOps = MdOp.scanMdTag(((String)input.get(6)).toUpperCase());
            } catch(FormatException e) {
            	warn("errors parsing MD string (input: " + (String)input.get(6) + "), silently IGNORING read, exception: "+e.toString(), PigWarning.UDF_WARNING_3);
		return null;
            }

	    DataBag output = mBagFactory.newDefaultBag();

	    // NOTE: code based on copy&paste from Seal AbstractTaggedMapping::calculateReferenceMatches

	    if (mdOps.isEmpty()) {
		//throw new IOException("no MD operators extracted from tag! (tag: " + (String)input.get(6) + ")");
	    	warn("no MD operators extracted from tag! (tag: " + (String)input.get(6) + "), silently IGNORING read", PigWarning.UDF_WARNING_3);
		return null;
            }

	    if (!compareLengthCigarMd()) {
		warn("CIGAR, MD and sequence lengths do not match! ignoring read! (CIGAR: " + (String)input.get(4) + " MD: " + (String)input.get(6) + " seq: " + sequence + ")", PigWarning.UDF_WARNING_1);
		return null;
	    }
		
	    Iterator<MdOp> mdIt = mdOps.iterator();

	    MdOp mdOp = mdIt.next();
	    int mdOpConsumed = 0; // the number of positions within the current mdOp that have been consumed.
	    int seqpos = 0;
	    int refpos = (((Integer)input.get(3)).intValue());
	    String pileuppref = ("^" + mapping_quality);
	    String pileuppof = "";

	    Tuple prev_tpl = null; // used for insertions and deletions to join their records; always set to last created tuple!
		
	    for (AlignOp alignOp: alignment) {
		if (alignOp.getType() == AlignOp.Type.Match) {

		    int positionsToCover = alignOp.getLen();

		    while (positionsToCover > 0 && mdOp != null) {
			if (mdOp.getType() == MdOp.Type.Delete) {
			    //mdOp = mdIt.next(); // skip it

			    throw new IOException("BUG or bad data?? found MD deletion while parsing CIGAR match! CIGAR: " + AlignOp.cigarStr(alignment) + "; MD: " + (String)input.get(6) + "; read: " + sequence + "; seqpos: "+seqpos+"; mdOpConsumed: "+mdOpConsumed+"; positionsToCover: "+positionsToCover);

			} else {

			    if(prev_tpl != null) {
                        	output.add(prev_tpl);
                        	prev_tpl = null;
                    	    }

			    // must be a match or a mismatch
			    boolean match = mdOp.getType() == MdOp.Type.Match;
			    int consumed = Math.min(mdOp.getLen() - mdOpConsumed, positionsToCover);

			    if(!deletionTuples.isEmpty()) {
			    	for (Tuple tpl: deletionTuples) {
					tpl.set(4, basequal.substring(seqpos, seqpos+1));
					output.add(tpl);
				}
				deletionTuples.clear();
			    }

			    for (int i = 0; i < consumed; i++) {

				/*if(refPositions.get(seqpos) < 0)
					throw new IOException("BUG or bad data?? unknown refpos inside match/mismatch! CIGAR: " + AlignOp.cigarStr(alignment) + "; MD: " + (String)input.get(6) + "; read: " + sequence + "; seqpos: "+seqpos+"; mdOpConsumed: "+mdOpConsumed+"; positionsToCover: "+positionsToCover);*/

				Tuple tpl = TupleFactory.getInstance().newTuple(5);
				tpl.set(0, (String)input.get(2));
				tpl.set(1, refpos++); //refPositions.get(seqpos));

				if(match) { // reference and read have matching bases
				    tpl.set(2, sequence.substring(seqpos, seqpos+1));

				    if(!mapping.isOnReverse()) // matching on forward strand
					pileuppref += ".";
				    else
					pileuppref += ","; // matching on reverse strand
				} else {
				    tpl.set(2, mdOp.getSeq().substring(i, i+1));

				    if(!mapping.isOnReverse()) // mismatch on forward strand
					pileuppref += sequence.substring(seqpos, seqpos+1);
				    //pileuppref += mdOp.getSeq().substring(i, i+1).toUpperCase();
				    else
					pileuppref += sequence.substring(seqpos, seqpos+1).toLowerCase(); // mismatch on reverse strand
				    //pileuppref += mdOp.getSeq().substring(i, i+1).toLowerCase(); // mismatch on reverse strand
				}

				if(seqpos == sequence.length()-1)
				    pileuppof = "$";

				tpl.set(3, pileuppref+pileuppof);
				tpl.set(4, basequal.substring(seqpos, seqpos+1));
				
				if(i < consumed-1)
				    output.add(tpl);
				else
				    prev_tpl = tpl;

				seqpos++;
				pileuppref = "";
			    }

			    positionsToCover -= consumed;
			    mdOpConsumed += consumed;
			    if (mdOpConsumed >= mdOp.getLen()) { // operator consumed.  Advance to next
				mdOpConsumed = 0;
				if (mdIt.hasNext())
				    mdOp = mdIt.next();
				else
				    mdOp = null;
			    }
			}
		    }

		    if (positionsToCover > 0 && mdOp == null)
			throw new IOException("BUG or bad data?? Found more read positions than was covered by the MD tag. CIGAR: " + AlignOp.cigarStr(alignment) + "; MD: " + (String)input.get(6) + "; read: " + sequence);
		}

		else if(alignOp.getType() == AlignOp.Type.Insert) {
		    //int consumed = Math.min(mdOp.getLen() - mdOpConsumed, positionsToCover);
		    //int consumed = alignOp.getLen();

		    // NOTE!!! for now we ignore trailing insertions!!!
		    if(seqpos == 0) {
			seqpos += alignOp.getLen();
			continue;
		    }

		    //if(prev_tpl == null)
		    //throw new IOException("BUG or bad data?? Found insertion before first CIGAR match! CIGAR: " + AlignOp.cigarStr(alignment) + "; MD: " + (String)input.get(6) + "; read: " + sequence);

		    Tuple tpl ;

		    if(prev_tpl == null) {
			tpl = TupleFactory.getInstance().newTuple(5);

			tpl.set(0, (String)input.get(2));
			tpl.set(1, refpos-1);
			tpl.set(2, null); // here should be the last reference base of the "previous" AlignOp
			tpl.set(4, null); // note: it seems samtools silently drops base qualities of inserted bases
		    } else {
			tpl = prev_tpl;
			pileuppref = (String)tpl.get(3);
		    }

		    /*if(seqpos > 0 && refPositions.get(seqpos-1) >= 0) {
			tpl.set(1, refPositions.get(seqpos-1)); // NOTE: this may cause problems with grouping!!
		    } else {
			throw new IOException("BUG or bad data?? Found insertion before first CIGAR match! CIGAR: " + AlignOp.cigarStr(alignment) + "; MD: " + (String)input.get(6) + "; read: " + sequence);
		    }*/

		    /*String insert_bases = "";
		      String insert_qualities = "";

		      for (int i = consumed; i > 0; --i) {
		      if(!mapping.isOnReverse()) // matching on forward strand
		      insert_bases += sequence.substring(seqpos, seqpos+1).toUpperCase();
		      else
		      insert_bases += sequence.substring(seqpos, seqpos+1).toLowerCase();

		      insert_qualities += basequal.substring(seqpos, seqpos+1);
		      seqpos++;
		      }*/

		    if(seqpos + alignOp.getLen() == sequence.length())
			pileuppof = "$";

	            if(mapping.isOnReverse())
		    	tpl.set(3, pileuppref+"+"+alignOp.getLen()+sequence.substring(seqpos,seqpos+alignOp.getLen()).toLowerCase()+pileuppof);
		    else
			tpl.set(3, pileuppref+"+"+alignOp.getLen()+sequence.substring(seqpos,seqpos+alignOp.getLen())+pileuppof);
			
		    //tpl.set(4, basequal.substring(seqpos, seqpos+alignOp.getLen()));
		    //tpl.set(4, null); // note: it seems samtools silently drops base
		    // qualities of inserted bases

		    seqpos += alignOp.getLen();

		    /*tpl.set(3, pileuppref+"+"+insert_bases.length()+insert_bases+pileuppof);
		      tpl.set(4, insert_qualities);*/

		    output.add(tpl);
		    prev_tpl = null;

		    pileuppref = "";

		    /*positionsToCover -= consumed;
		      mdOpConsumed += consumed;
		      if (mdOpConsumed >= mdOp.getLen()) { // operator consumed.  Advance to next
		      mdOpConsumed = 0;
		      if (mdIt.hasNext())
		      mdOp = mdIt.next();
		      else
		      mdOp = null;
		      }*/
                   	
		}  else if(alignOp.getType() == AlignOp.Type.Delete) {
		    /*int positionsToCover = alignOp.getLen();

		      while (positionsToCover > 0 && mdOp != null) */

		    //int consumed = Math.min(mdOp.getLen() - mdOpConsumed, positionsToCover);
		    //int consumed = alignOp.getLen();

		    //if(true) {

		    if(mdOp == null || mdOp.getType() != MdOp.Type.Delete || mdOp.getLen() != alignOp.getLen()) {
			throw new IOException("BUG or bad data?? Could not find matching MD deletion after finding CIGAR deletion! CIGAR: " + AlignOp.cigarStr(alignment) + "; MD: " + (String)input.get(6) + "; read: " + sequence+ "; MdOp: "+(mdOp==null?"null":mdOp.toString()));
		    }
	
		    Tuple tpl ;

		    if(prev_tpl == null) {
			tpl = TupleFactory.getInstance().newTuple(5);

			tpl.set(0, (String)input.get(2));
			tpl.set(1, refpos-1);
			tpl.set(2, null); // here should be the last reference base of the "previous" AlignOp
			tpl.set(4, null);
		    } else {
			tpl = prev_tpl;
			pileuppref = (String)tpl.get(3);
		    }

		    String deleted_bases = mdOp.getSeq();

		    if(mapping.isOnReverse())
			deleted_bases = deleted_bases.toLowerCase();
		    else
			deleted_bases = deleted_bases.toUpperCase();

		    /*for (int i = consumed; i > 0; --i) {
		      if(!mapping.isOnReverse()) // matching on forward strand
		      insert_bases += sequence.substring(seqpos, seqpos+1).toUpperCase();
		      else
		      insert_bases += sequence.substring(seqpos, seqpos+1).toLowerCase();

		      insert_qualities += basequal.substring(seqpos, seqpos+1);
		      seqpos++;
		      }*/

		    /*if(seqpos + alignOp.getLen() == sequence.length()-1)
			pileuppof = "$";*/

		    tpl.set(3, pileuppref+"-"+alignOp.getLen()+deleted_bases+pileuppof);

		    output.add(tpl);
		    prev_tpl = null;

		    //int start_deletion = refpos; //refPositions.get(seqpos-1);

		    for(int i=0;i<mdOp.getLen();i++) {
			Tuple dtpl = TupleFactory.getInstance().newTuple(5);
			dtpl.set(0, (String)input.get(2));
			dtpl.set(1, refpos++); //start_deletion+i+1);
			dtpl.set(2, deleted_bases.substring(i,i+1).toUpperCase());
			dtpl.set(3, "*");
			//dtpl.set(4, mapping_quality);
			//output.add(dtpl);
			deletionTuples.add(dtpl);
		    }

		    pileuppref = "";

		    if (mdIt.hasNext())
                      mdOp = mdIt.next();
                    else
                      mdOp = null;

		    /*positionsToCover -= consumed;
		      mdOpConsumed += consumed;
		      if (mdOpConsumed >= mdOp.getLen()) { // operator consumed.  Advance to next
		      mdOpConsumed = 0;
		      if (mdIt.hasNext())
		      mdOp = mdIt.next();
		      else
		      mdOp = null;
		      }*/	
		    
		} else if(alignOp.getType() == AlignOp.Type.SoftClip) {
		    if(true) {

		    Tuple tpl = TupleFactory.getInstance().newTuple(5);

		    /*if(seqpos > 0 && refPositions.get(seqpos-1)>=0) {
                        tpl.set(1, refPositions.get(seqpos-1)); // NOTE: this may cause problems with grouping!!
                    } else {
                        throw new IOException("BUG or bad data?? Found softclip before first CIGAR match! CIGAR: " + AlignOp.cigarStr(alignment) + "; MD: " + (String)input.get(6) + "; read: " + sequence);
                    }*/
		    tpl.set(1, refpos);

		    tpl.set(0, (String)input.get(2));
		    //tpl.set(1, refPositions.get(seqpos-1)); // NOTE: this may cause problems with grouping!!
		    // since the inserted sequence of bases should appear together with the entry for the previous
		    // position before the insert??
		    tpl.set(2, null); // here should be the last reference base of the "previous" AlignOp
		    tpl.set(3, "$");
		    tpl.set(4, null);

		    output.add(tpl);

		    pileuppref = ("^" + mapping_quality);
		    /*for (int i = alignOp.getLen(); i > 0; --i)
		      dest.add(null);
		      }
		      // else the op doesn't affect the read*/
		}
		/*} catch (NoSuchFieldException e) {
		  throw new RuntimeException(e.getMessage());
		  }*/
		
		/*boolean processing_insert = false;

		  for (int i = 0; i < seq.length(); ++i) {
		  int pos = refPositions.get(i);

		  if (pos >= 0) {
		  Tuple tpl = TupleFactory.getInstance().newTuple(4);
		  tpl.set(0, mapping.getContig());
		  tpl.set(1, pos);
		  tpl.set(2, sequence.substring(i, i+1));
		  tpl.set(3, basequal.substring(i, i+1));

		  if(matches.get(i) != null) {
		  if(matches.get(i).booleanValue()) { // found match
		  if(!mapping.isOnReverse()) // matching on forward strand
		  tpl.set(4, ".");
		  else
		  tpl.set(4, ","); // matching on reverse strand

		  if(i < seq.length()-1) { // check whether this is the start of an insertion
		  int insert_length = 1;
		  String insertion_seq = "";

		  while(i+insert_length < seq.length() && matches.get(i+insert_length) == null) {
		  insert_length++;
		  insertion_seq += sequence.substring(i+insert_length, i+insert_length+1);
		  }
		  }
		  } else { // mismatch or insert
				
		  }
		  }

		  output.add(tpl);
		  }
		  }*/
	          }
	    }


	    if(prev_tpl != null)
		output.add(prev_tpl);

	    return output;

	} catch(Exception e) {
	    throw new IOException("Caught exception processing input row " + e.toString());
	}
    }

    @Override
    public Schema outputSchema(Schema input) {
	try{
	    Schema bagSchema = new Schema();
	    bagSchema.add(new Schema.FieldSchema("chr", DataType.CHARARRAY));
	    bagSchema.add(new Schema.FieldSchema("pos", DataType.INTEGER));
	    bagSchema.add(new Schema.FieldSchema("refbase", DataType.CHARARRAY));
	    bagSchema.add(new Schema.FieldSchema("pileup", DataType.CHARARRAY));
	    bagSchema.add(new Schema.FieldSchema("qual", DataType.CHARARRAY));

	    return new Schema(new Schema.FieldSchema(getSchemaName(this.getClass().getName().toLowerCase(), input), bagSchema, DataType.BAG));
	}catch (Exception e){
	    return null;
	}
    }
}