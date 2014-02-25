package water.util;

import water.H2O;
import water.H2ONode;
import water.Key;
import water.MRTask2;
import water.fvec.*;

import java.util.Random;

import static water.util.Utils.getDeterRNG;

public class MRUtils {

  /**
   * Sample rows from a frame.
   * Can be unlucky for small sampling fractions - will continue calling itself until at least 1 row is returned.
   * @param fr Input frame
   * @param rows Approximate number of rows to sample (across all chunks)
   * @param seed Seed for RNG
   * @return Sampled frame
   */
  public static Frame sampleFrame(Frame fr, final long rows, final long seed) {
    if (fr == null) return null;
    final float fraction = rows > 0 ? (float)rows / fr.numRows() : 1.f;
    if (fraction >= 1.f) return fr;
    Frame r = new MRTask2() {
      @Override
      public void map(Chunk[] cs, NewChunk[] ncs) {
        final Random rng = getDeterRNG(seed + cs[0].cidx());
        for (int r = 0; r < cs[0]._len; r++)
          if (rng.nextFloat() < fraction) {
            for (int i = 0; i < ncs.length; i++) {
              ncs[i].addNum(cs[i].at0(r));
            }
          }
      }
    }.doAll(fr.numCols(), fr).outputFrame(fr.names(), fr.domains());
    if (r.numRows() == 0) {
      Log.warn("You asked for " + rows + " rows (out of " + fr.numRows() + "), but you got none (seed=" + seed + ").");
      Log.warn("Let's try again. You've gotta ask yourself a question: \"Do I feel lucky?\"");
      return sampleFrame(fr, rows, seed+1);
    }
    return r;
  }

  /**
   * Row-wise shuffle of a frame (only shuffles rows inside of each chunk)
   * @param fr Input frame
   * @return Shuffled frame
   */
  public static Frame shuffleFramePerChunk(Frame fr, final long seed) {
    Frame r = new MRTask2() {
      @Override
      public void map(Chunk[] cs, NewChunk[] ncs) {
        long[] idx = new long[cs[0]._len];
        for (int r=0; r<idx.length; ++r) idx[r] = r;
        Utils.shuffleArray(idx, seed);
        for (int r=0; r<idx.length; ++r) {
          for (int i = 0; i < ncs.length; i++) {
            ncs[i].addNum(cs[i].at0((int)idx[r]));
          }
        }
      }
    }.doAll(fr.numCols(), fr).outputFrame(fr.names(), fr.domains());
    return r;
  }

  /**
   * Global redistribution of a Frame (balancing of chunks), done my calling process (all-to-one + one-to-all)
   * Note: this deletes the input Frame from the DKV
   * @param fr Input frame
   * @param seed RNG seed
   * @param shuffle whether to shuffle the data globally
   * @return Shuffled frame
   */
  // master node collects all rows, and distributes them across the cluster - slow
  public static Frame shuffleAndBalance(final Frame fr, long seed, final boolean shuffle) {
    int cores = 0;
    for( H2ONode node : H2O.CLOUD._memary )
      cores += node._heartbeat._num_cpus;
    final int splits = 4*cores;

    long[] idx = null;
    if (shuffle) {
      idx = new long[(int)fr.numRows()]; //HACK: should be a long
      for (int r=0; r<idx.length; ++r) idx[r] = r;
      Utils.shuffleArray(idx, seed);
    }

    Vec[] vecs = fr.vecs();
    if( vecs[0].nChunks() < splits || shuffle ) {
      Key keys[] = new Vec.VectorGroup().addVecs(vecs.length);
      for( int v = 0; v < vecs.length; v++ ) {
        AppendableVec vec = new AppendableVec(keys[v]);
        final long rows = fr.numRows();
        for( int split = 0; split < splits; split++ ) {
          long off = rows * split / splits;
          long lim = rows * (split + 1) / splits;
          assert(lim <= Integer.MAX_VALUE);
          NewChunk chunk = new NewChunk(vec, split);
          for( long r = off; r < lim; r++ ) {
            if (shuffle) chunk.addNum(fr.vecs()[v].at(idx[(int)r]));
            else chunk.addNum(fr.vecs()[v].at(r));
          }
          chunk.close(split, null);
        }
        Vec t = vec.close(null);
        t._domain = vecs[v]._domain;
        vecs[v] = t;
      }
    }
    fr.reloadVecs();
    return new Frame(fr.names(), vecs);
  }
}
