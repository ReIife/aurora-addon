package com.aurora.addon.modules.elytra.elytracollect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.world.phys.Vec3;

public final class CalculateRoute {
   private CalculateRoute() {
   }

   public static List<Vec3> calculateBottleneck(List<Vec3> pts, double px, double pz, int ilsIter, int k, double epsilon) {
      int n = pts.size();
      if (n == 0) {
         return Collections.emptyList();
      } else if (n == 1) {
         return new ArrayList<>(pts);
      } else {
         float[][] dist = new float[n][n];

         for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
               float dx = (float)(pts.get(i).x - pts.get(j).x);
               float dz = (float)(pts.get(i).z - pts.get(j).z);
               dist[i][j] = dist[j][i] = (float)Math.sqrt(dx * dx + dz * dz);
            }
         }

         double B = bottleneckKruskal(n, dist);
         if (B <= 0.0) {
            B = 1.0;
         }

         float maxHop = (float)(B * (1.0 + Math.max(0.0, epsilon)));
         int kk = Math.max(4, Math.min(k, n - 1));
         int[][] knn = buildKNN(n, dist, kk);
         int start = 0;
         double bestD = Double.MAX_VALUE;

         for (int i = 0; i < n; i++) {
            double dx = pts.get(i).x - px;
            double dz = pts.get(i).z - pz;
            double d = dx * dx + dz * dz;
            if (d < bestD) {
               bestD = d;
               start = i;
            }
         }

         List<Integer> path = constrainedNN(n, dist, knn, start, maxHop);
         int[] pos = new int[n];
         int ix = 0;

         while (ix < n) {
            pos[path.get(ix)] = ix++;
         }

         for (int round = 0; round < 25; round++) {
            boolean imp = false;

            while (twoOptPass(path, dist, maxHop, knn, pos)) {
               imp = true;
            }

            while (orOptPass(path, dist, maxHop, pos)) {
               imp = true;
            }

            if (!imp) {
               break;
            }
         }

         if (ilsIter > 0) {
            Random rng = new Random(24301L);
            List<Integer> best = new ArrayList<>(path);
            double bestLen = pathLength(best, dist);
            List<Integer> current = new ArrayList<>(path);

            for (int it = 0; it < ilsIter; it++) {
               List<Integer> p = doubleBridge(current, rng);
               int[] ppos = new int[n];
               int ixx = 0;

               while (ixx < n) {
                  ppos[p.get(ixx)] = ixx++;
               }

               for (int r = 0; r < 25; r++) {
                  boolean imp = twoOptPass(p, dist, maxHop, knn, ppos);
                  if (orOptPass(p, dist, maxHop, ppos)) {
                     imp = true;
                  }

                  if (!imp) {
                     break;
                  }
               }

               double len = pathLength(p, dist);
               if (len < bestLen) {
                  bestLen = len;
                  best = new ArrayList<>(p);
                  current = p;
               } else if (len < bestLen * 1.02) {
                  current = p;
               }
            }

            path = best;
         }

         List<Vec3> result = new ArrayList<>(n);

         for (int idx : path) {
            result.add(pts.get(idx));
         }

         return result;
      }
   }

   public static List<Vec3> calculateNearestNeighbor(List<Vec3> pts, double px, double pz) {
      int n = pts.size();
      List<Vec3> out = new ArrayList<>(n);
      boolean[] used = new boolean[n];

      while (out.size() < n) {
         int best = -1;
         double bestD = Double.MAX_VALUE;

         for (int i = 0; i < n; i++) {
            if (!used[i]) {
               double dx;
               double dz;
               if (out.isEmpty()) {
                  dx = pts.get(i).x - px;
                  dz = pts.get(i).z - pz;
               } else {
                  Vec3 last = out.getLast();
                  dx = pts.get(i).x - last.x;
                  dz = pts.get(i).z - last.z;
               }

               double d = dx * dx + dz * dz;
               if (d < bestD) {
                  bestD = d;
                  best = i;
               }
            }
         }

         if (best < 0) {
            break;
         }

         used[best] = true;
         out.add(pts.get(best));
      }

      return out;
   }

   public static double[] stats(List<Vec3> route, double px, double pz) {
      if (route.isEmpty()) {
         return new double[]{0.0, 0.0};
      } else {
         double maxD = 0.0;
         double sum = 0.0;
         double prevX = px;
         double prevZ = pz;

         for (Vec3 p : route) {
            double dx = p.x - prevX;
            double dz = p.z - prevZ;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > maxD) {
               maxD = d;
            }

            sum += d;
            prevX = p.x;
            prevZ = p.z;
         }

         return new double[]{maxD, sum};
      }
   }

   private static int find(int[] p, int x) {
      while (p[x] != x) {
         p[x] = p[p[x]];
         x = p[x];
      }

      return x;
   }

   private static double bottleneckKruskal(int n, float[][] dist) {
      if (n <= 1) {
         return 0.0;
      } else {
         int E = n * (n - 1) / 2;
         CalculateRoute.KEdge[] edges = new CalculateRoute.KEdge[E];
         int idx = 0;

         for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
               edges[idx++] = new CalculateRoute.KEdge(i, j, dist[i][j]);
            }
         }

         Arrays.sort((Object[])edges);
         int[] parent = new int[n];
         int[] deg = new int[n];
         int i = 0;

         while (i < n) {
            parent[i] = i++;
         }

         i = 0;

         for (CalculateRoute.KEdge e : edges) {
            int ru = find(parent, e.u);
            int rv = find(parent, e.v);
            if (ru != rv && deg[e.u] < 2 && deg[e.v] < 2) {
               parent[ru] = rv;
               deg[e.u]++;
               deg[e.v]++;
               if (++i == n - 1) {
                  return e.w;
               }
            }
         }

         return edges[edges.length - 1].w;
      }
   }

   private static int[][] buildKNN(int n, float[][] dist, int k) {
      int kk = Math.min(k, n - 1);
      int[][] knn = new int[n][kk];
      Integer[] order = new Integer[n];

      for (int i = 0; i < n; i++) {
         order[i] = i;
      }

      for (int i = 0; i < n; i++) {
         int fi = i;
         Arrays.sort(order, (a, b) -> Float.compare(dist[fi][a], dist[fi][b]));
         int c = 0;

         for (int idx = 0; idx < n && c < kk; idx++) {
            if (order[idx] != i) {
               knn[i][c++] = order[idx];
            }
         }
      }

      return knn;
   }

   private static List<Integer> constrainedNN(int n, float[][] dist, int[][] knn, int start, float maxHop) {
      List<Integer> path = new ArrayList<>(n);
      boolean[] used = new boolean[n];
      path.add(start);
      used[start] = true;
      int cur = start;

      for (int step = 1; step < n; step++) {
         int best = -1;
         float bestD = Float.MAX_VALUE;

         for (int cand : knn[cur]) {
            if (!used[cand]) {
               float d = dist[cur][cand];
               if (d <= maxHop + 0.001F && d < bestD) {
                  bestD = d;
                  best = cand;
               }
            }
         }

         if (best < 0) {
            for (int i = 0; i < n; i++) {
               if (!used[i]) {
                  float d = dist[cur][i];
                  if (d < bestD) {
                     bestD = d;
                     best = i;
                  }
               }
            }
         }

         if (best < 0) {
            break;
         }

         path.add(best);
         used[best] = true;
         cur = best;
      }

      return path;
   }

   private static boolean twoOptPass(List<Integer> path, float[][] dist, float maxHop, int[][] knn, int[] pos) {
      int n = path.size();
      if (n < 4) {
         return false;
      } else {
         boolean improved = false;

         for (int i = 0; i < n - 1; i++) {
            int a = path.get(i);

            for (int cand : knn[a]) {
               int j = pos[cand];
               if (j > i + 1) {
                  float newD1 = dist[a][path.get(j)];
                  if (!(newD1 > maxHop + 0.001F)) {
                     float delta;
                     if (j < n - 1) {
                        float newD2 = dist[path.get(i + 1)][path.get(j + 1)];
                        if (newD2 > maxHop + 0.001F) {
                           continue;
                        }

                        float oldD1 = dist[a][path.get(i + 1)];
                        float oldD2 = dist[path.get(j)][path.get(j + 1)];
                        delta = newD1 + newD2 - oldD1 - oldD2;
                     } else {
                        float oldD1 = dist[a][path.get(i + 1)];
                        delta = newD1 - oldD1;
                     }

                     if (delta < -0.001F) {
                        reverseSegment(path, i + 1, j, pos);
                        improved = true;
                        break;
                     }
                  }
               }
            }
         }

         return improved;
      }
   }

   private static void reverseSegment(List<Integer> path, int i, int j, int[] pos) {
      while (i < j) {
         int a = path.get(i);
         int b = path.get(j);
         path.set(i, b);
         path.set(j, a);
         pos[a] = j;
         pos[b] = i++;
         j--;
      }
   }

   private static boolean orOptPass(List<Integer> path, float[][] dist, float maxHop, int[] pos) {
      int n = path.size();
      if (n < 4) {
         return false;
      } else {
         for (int i = 0; i < n; i++) {
            int v = path.get(i);
            int prev = i > 0 ? path.get(i - 1) : -1;
            int next = i < n - 1 ? path.get(i + 1) : -1;
            float removeGain;
            if (prev >= 0 && next >= 0) {
               removeGain = dist[prev][v] + dist[v][next] - dist[prev][next];
            } else if (prev >= 0) {
               removeGain = dist[prev][v];
            } else {
               if (next < 0) {
                  continue;
               }

               removeGain = dist[v][next];
            }

            for (int j = 0; j < n - 1; j++) {
               if (j != i - 1 && j != i) {
                  int jp = path.get(j);
                  int jn = path.get(j + 1);
                  if (!(dist[jp][v] > maxHop + 0.001F) && !(dist[v][jn] > maxHop + 0.001F)) {
                     float add = dist[jp][v] + dist[v][jn] - dist[jp][jn];
                     if (add - removeGain < -0.001F) {
                        List<Integer> np = new ArrayList<>(n);

                        for (int k = 0; k < n; k++) {
                           if (k != i) {
                              np.add(path.get(k));
                              if (k == j) {
                                 np.add(v);
                              }
                           }
                        }

                        path.clear();
                        path.addAll(np);
                        int kx = 0;

                        while (kx < n) {
                           pos[path.get(kx)] = kx++;
                        }

                        return true;
                     }
                  }
               }
            }
         }

         return false;
      }
   }

   private static List<Integer> doubleBridge(List<Integer> path, Random rng) {
      int n = path.size();
      if (n < 8) {
         return new ArrayList<>(path);
      } else {
         int a = 1 + rng.nextInt(n - 3);
         int b = a + 1 + rng.nextInt(n - a - 2);
         int c = b + 1 + rng.nextInt(n - b - 1);
         List<Integer> r = new ArrayList<>(n);
         r.addAll(path.subList(0, a));
         r.addAll(path.subList(b, c));
         r.addAll(path.subList(a, b));
         r.addAll(path.subList(c, n));
         return r;
      }
   }

   private static double pathLength(List<Integer> path, float[][] dist) {
      double sum = 0.0;

      for (int i = 0; i < path.size() - 1; i++) {
         sum += dist[path.get(i)][path.get(i + 1)];
      }

      return sum;
   }

   private static final class KEdge implements Comparable<CalculateRoute.KEdge> {
      final int u;
      final int v;
      final float w;

      KEdge(int u, int v, float w) {
         this.u = u;
         this.v = v;
         this.w = w;
      }

      public int compareTo(CalculateRoute.KEdge o) {
         return Float.compare(this.w, o.w);
      }
   }
}
