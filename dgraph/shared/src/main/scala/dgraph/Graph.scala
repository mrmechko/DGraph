package dgraph

import java.util.NoSuchElementException

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.collection.mutable

case class Node[N](value:N, id:Int)

case class DEdge[E](value:E, from:Int, to:Int){
  def getID = (from,to)
}

case class DGraph[N,E] (nodes:Map[Int, Node[N]], edges:TreeMap[(Int,Int), DEdge[E]],
                        inMap:Map[Int,IndexedSeq[Int]], outMap:Map[Int,IndexedSeq[Int]]) {

  lazy val roots = inMap.filter(_._2.size == 0).map { case(ky,mp) => nodes(ky) }


  def childs(n:Int) = if(outMap.contains(n)) outMap(n).map(nodes(_)) else IndexedSeq.empty

  def childsEdges(n:Int) = {
    if (outMap.contains(n)) outMap(n).map(x => edges((n, x)))
    else IndexedSeq.empty
  }

  def addNode(node:Node[N]):DGraph[N,E] = {
    this.copy(
      nodes = nodes.updated(node.id,node),
      inMap = inMap.updated(node.id,inMap.getOrElse(node.id, IndexedSeq.empty[Int])),
      outMap = outMap.updated(node.id,outMap.getOrElse(node.id, IndexedSeq.empty[Int]))
    )
  }

  def addEdge(edge:DEdge[E]):Option[DGraph[N,E]] = {
    if(nodes.contains(edge.from) && nodes.contains(edge.to)) {
      Some(
        this.copy(
          edges = edges.updated((edge.from, edge.to), edge),
          inMap = inMap.updated(edge.to, inMap(edge.to) :+ edge.from),
          outMap = outMap.updated(edge.from, outMap(edge.from) :+ edge.to)
        )
      )
    } else None
  }

  def updated(from: Node[N], to: Node[N], edge:DEdge[E]): DGraph[N, E] = {
    val q0 = this.copy(nodes = this.nodes ++ List((from.id,from), (to.id,to)))

    val inM = if(inMap.contains(edge.to)) edge.from +: inMap(edge.to) else IndexedSeq(edge.from)
    val outM = if(outMap.contains(edge.from)) edge.to +: outMap(edge.from) else IndexedSeq(edge.to)

    q0.copy(
      edges = edges.updated((edge.from, edge.to), edge),
      inMap = inMap.updated(edge.to, inM),
      outMap = outMap.updated(edge.from, outM)
    )
  }

//  def mtch(q:DGraph[NodeMatchLike[N] ,EdgeMatchLike[E]]):List[DGraph[N ,E]] =
//    GraphMatch.mtch(this, q).map(x => x.getMatchedGraph(this,q))

//  def mtch(q: QNode[NodeMatchLike[N] ,EdgeMatchLike[E]]):List[DGraph[N ,E]] = {
//    val q1 = DGraph.from(q)
//    mtch(q1)
//  }

  def containsNode(n:N):Boolean = nodes.values.filter(_.value == n).size > 0
  def containsEdge(e:E):Boolean = edges.values.filter(_.value == e).size > 0
  //def contains(query:DGraph[NodeMatchLike[N], EdgeMatchLike[E]]):Boolean = GraphMatch.mtch(this,query).size > 0
  def contains(query:QNodeLike[NodeMatchLike[N], EdgeMatchLike[E]]):Boolean = {
   val q = DGraph.from(query)
   GraphMatch.mtch(this, q).size > 0
  }

  def mapByNodes[M](mapper:N => M):DGraph[M, E] = {
    val newNodes = nodes.map { case(i,n) => (n.id -> Node(mapper(n.value), n.id))}
    DGraph[M, E](newNodes, this.edges, this.inMap, this.outMap)
  }

  def mapByEdges[EE](mapper:E => EE):DGraph[N, EE] = {
    val newEdges = edges.map { case((f,t),e) => ((f,t) -> DEdge(mapper(e.value), f, t))}
    DGraph[N, EE](this.nodes, newEdges, this.inMap, this.outMap)
  }

  def map[M,EE](nodeMapper: N => M , edgeMapper: E => EE) = {
    val newNodes = nodes.map { case(i,n) => (n.id -> Node(nodeMapper(n.value), n.id))}
    val newEdges = edges.map { case((f,t),e) => ((f,t) -> DEdge(edgeMapper(e.value), f, t))}
    DGraph[M, EE](newNodes, newEdges, this.inMap, this.outMap)
  }

  def filterNodes(nf: N => Boolean) = nodes.values.filter(x => nf(x.value))
  def filterEdges(ef: E => Boolean) = edges.values.filter(x => ef(x.value))
  def filter(query:DGraph[NodeMatchLike[N], EdgeMatchLike[E]]):List[DGraph[N,E]] = {
    GraphMatch.mtch(this,query).map(_.getMatchedGraph(this,query)._1)
  }
//  def filter(query:QNodeLike[NodeMatchLike[N], EdgeMatchLike[E]]):List[DGraph[N,E]] = {
//    val q = DGraph.from(query)
//    GraphMatch.mtch(this,q).map(_.getMatchedGraph(this,q)._1)
//  }


  def mapDFS[NP, EP](startNode:Node[N], nodeMapper: N => NP = identity _ , edgeMapper: E => EP = identity _, avoidLoop:Boolean = true) =
    traverse(startNode,nodeMapper,edgeMapper,true,avoidLoop)

  def mapBFS[NP, EP](startNode:Node[N], nodeMapper: N => NP = identity _ , edgeMapper: E => EP = identity _, avoidLoop:Boolean = true) =
    traverse(startNode,nodeMapper,edgeMapper,false,avoidLoop)

  def foreachDFS[NP, EP](startNode:Node[N], nodeMapper: N => NP = identity _ , edgeMapper: E => EP = identity _, avoidLoop:Boolean = true) = {
    traverse(startNode, nodeMapper, edgeMapper, true, avoidLoop)
    Unit
  }

  def foreachBFS[NP, EP](startNode:Node[N], nodeMapper: N => NP = identity _ , edgeMapper: E => EP = identity _, avoidLoop:Boolean = true) = {
    traverse(startNode, nodeMapper, edgeMapper, false, avoidLoop)
    Unit
  }

  private def gfold[NB,EB](startNode:Node[N], nz:NB, ez: EB, isDFS:Boolean = true, avoidLoop:Boolean = true)(nodeOp:(NB, N) => NB, edgeOp:(EB, E) => EB) = {
    var resultN = nz
    var resultE = ez
    this traverse(startNode, x => resultN = nodeOp(resultN,x), x => resultE = edgeOp(resultE,x), isDFS, avoidLoop)
    (resultN,resultE)
  }

  def foldDFS[NB,EB](startNode:Node[N], nz:NB, ez: EB, avoidLoop:Boolean = true)(nodeOp:(NB, N) => NB, edgeOp:(EB, E) => EB) = {
    gfold[NB,EB](startNode, nz, ez, true, avoidLoop)(nodeOp, edgeOp)
  }

  def foldBFS[NB,EB](startNode:Node[N], nz:NB, ez: EB, avoidLoop:Boolean = true)(nodeOp:(NB, N) => NB, edgeOp:(EB, E) => EB) = {
    gfold[NB,EB](startNode, nz, ez, false, avoidLoop)(nodeOp, edgeOp)
  }

  def foldNodesDFS[NB](startNode:Node[N], nz:NB, avoidLoop:Boolean = true)(nodeOp:(NB, N) => NB) = {
    gfold(startNode, nz, Unit, true, avoidLoop)(nodeOp, (x,y) => Unit)._1
  }

  def foldNodesBFS[NB](startNode:Node[N], nz:NB, avoidLoop:Boolean = true)(nodeOp:(NB, N) => NB) = {
    gfold(startNode, nz, Unit, false, avoidLoop)(nodeOp, (x,y) => Unit)._1
  }

  def foldEdgesDFS[NE](startNode:Node[N], ne:NE, avoidLoop:Boolean = true)(edgeOp:(NE, E) => NE) = {
    gfold(startNode, Unit, ne, true, avoidLoop)((x,y) => Unit,edgeOp)._2
  }

  private def traverse[NP,EP](node:Node[N], nodeF:(N => NP), edgeF:(E => EP),
                              isDFS:Boolean = true, avoidLoop:Boolean = true) = {

    if(nodes.contains(node.id)) {
      var nodes2visit = List(node.id)
      var edges2Visit = List.empty[(Int,Int)]
      val visitedNodes = mutable.Set.empty[Int]
      val visitedEdges = mutable.Set.empty[(Int,Int)]

      var res = DGraph.empty[NP,EP]

      while(!nodes2visit.isEmpty || !edges2Visit.isEmpty) {

        val currentNode = nodes2visit.headOption
       // println(s"Current Node: $currentNode")
        val outs = if(currentNode.isDefined) outMap(currentNode.get) else IndexedSeq.empty[Int]
        //
        //node

        if(avoidLoop && currentNode.isDefined) {
          nodes2visit =
            if(isDFS) outs.filter(o => !visitedNodes.contains(o)).toList ::: nodes2visit.tail
            else nodes2visit.tail ::: outs.filter(o => !visitedNodes.contains(o)).toList
        }
        else if(!avoidLoop) {
          nodes2visit = if(isDFS) outs.toList ::: nodes2visit.tail else nodes2visit.tail ::: outs.toList
        }

        if(currentNode.isDefined) {
          //println(s"adding Node $currentNode")
          visitedNodes.add(currentNode.get)
          res = res.addNode(Node(nodeF(nodes(currentNode.get).value), currentNode.get))
        }

        //
        //edge
        val edgeOut =
          if(avoidLoop && currentNode.isDefined)
            outs.filter(o => !visitedEdges.contains((currentNode.get,o))).toList
          else
            outs.toList

        if(!edges2Visit.isEmpty){
          val currentEdge = edges2Visit.head
          //println(s"adding edge $currentEdge")
          visitedEdges.add(currentEdge)
          res = res.addEdge(DEdge(edgeF(edges(currentEdge).value), currentEdge._1, currentEdge._2)).get
          if(currentNode.isDefined) {
            if(isDFS)
              edges2Visit = edgeOut.map(o => (currentNode.get, o)) ::: edges2Visit.tail
            else
              edges2Visit = edges2Visit.tail ::: edgeOut.map(o => (currentNode.get, o))
          }
          else edges2Visit = edges2Visit.tail
        }
        else if(currentNode.isDefined) {
          if(isDFS)
            edges2Visit = edgeOut.map(o => (currentNode.get,o)) ::: edges2Visit
          else
            edges2Visit = edges2Visit ::: edgeOut.map(o => (currentNode.get,o))
        }

      }
      //println("Exit!")
      res
    }
    else throw new NoSuchElementException("The node is not part of the graph")
  }

}

trait HalfEdgeLike[+E,+N] extends Product with Serializable

case class HalfEdge[E,N](e:E, n:QNodeLike[N,E]) extends HalfEdgeLike[E,N]
case object EmptyHalfEdge extends HalfEdgeLike[Nothing,Nothing]

trait QNodeLike[+N,+E] extends Product with Serializable

case class QNode[N,E](head:N ,out:HalfEdgeLike[E,N]*) extends QNodeLike[N,E]

case class QNodeMarker[N,E](head:N, marker:String, out:HalfEdgeLike[E,N]*) extends QNodeLike[N,E]

case class QNodeRef[N,E](ref:String, out:HalfEdgeLike[E,N]*) extends QNodeLike[N,E]


object DGraphDSL {

  case class PEdge[E](e:E) {
    def ->[N](qNode: QNodeLike[N,E]) = HalfEdge(e, qNode)
  }


  def --[E](ed:E):PEdge[E] = PEdge(ed)

  case class PEdgeMatch[E](e:E) {
    def ->[N](qNode: QNodeLike[NodeMatchLike[N],EdgeMatchLike[E]]) = HalfEdge(e, qNode)
  }


  implicit def tupConvert[N,E](tup: (E,QNodeLike[N,E])):HalfEdge[E,N] = HalfEdge(tup._1,tup._2)
  implicit def tupConvert3[N,E](tup: (N,String,HalfEdgeLike[E,N])) = QNodeMarker(tup._1,tup._2,tup._3)
  implicit def tupConvert33[N,E](tup: (N,String)) = QNodeMarker(tup._1,tup._2,EmptyHalfEdge)

  def Nd[N,E](n:N, edges:HalfEdgeLike[E,N]*) = QNode[N,E](n,edges:_*)
  def NdMark[N,E](n:N, marker:String, edges:HalfEdgeLike[E,N]*) = QNodeMarker[N,E](n,marker, edges:_*)
  def Ref[N,E](marker:String, edges:HalfEdgeLike[E,N]*) = QNodeRef[N,E](marker, edges:_*)


  def query[N,E](qNodes:QNodeLike[NodeMatchLike[N],EdgeMatchLike[E]]):DGraph[NodeMatchLike[N],EdgeMatchLike[E]] =
   DGraph.from(qNodes)

  def -?>[N,E](e:E =>Boolean, q:QNodeLike[NodeMatchLike[N],EdgeMatch[E]]):HalfEdgeLike[EdgeMatchLike[E],NodeMatchLike[N]] =
    HalfEdge[EdgeMatchLike[E],NodeMatchLike[N]](EdgeMatch(e),q)

  def <#[N,E](marker:String, edges:HalfEdgeLike[E,NodeMatchLike[N]]*) = QNodeRef[NodeMatchLike[N],E](marker,edges:_*)

  def <&[N,E](n:N => Boolean, edges:HalfEdgeLike[E,NodeMatchLike[N]]*) = QNode[NodeMatchLike[N],E](NodeMatchAND(n),edges:_*)

  def <&&[N,E](n:N => Boolean, marker:String, edges:HalfEdgeLike[E,NodeMatchLike[N]]*) =
    QNodeMarker[NodeMatchLike[N],E](NodeMatchAND(n),marker,edges:_*)

  def <|[N,E](n:N => Boolean, edges:HalfEdgeLike[E,NodeMatchLike[N]]*) = QNode[NodeMatchLike[N],E](NodeMatchOR(n),edges:_*)

  def <||[N,E](n:N => Boolean, marker:String, edges:HalfEdgeLike[E,NodeMatchLike[N]]*) =
    QNodeMarker[NodeMatchLike[N],E](NodeMatchOR(n),marker,edges:_*)

  def anyNode[N](n:N):Boolean = true
  def anyEdge[E](e:E):Boolean = true

}

object DGraph {

  def query[N,E](qNodes:QNodeLike[NodeMatchLike[N],EdgeMatchLike[E]]*):DGraph[NodeMatchLike[N],EdgeMatchLike[E]] = {
    from(qNodes:_*)
  }

  def from[N,E](nods:Map[Int, Node[N]], edgs:TreeMap[(Int,Int), DEdge[E]]):DGraph[N,E] = {
    val (inM, outM) = mkEdgeMaps(nods,edgs)
    DGraph(nods,edgs,inM,outM)
  }

  def from[N,E](qNodes:QNodeLike[N,E]*):DGraph[N,E] = {
    val markers = scala.collection.mutable.Map.empty[String, N]
    @tailrec
    def loopForNodes(nds:List[QNodeLike[N,E]]):Unit = {
      nds match {
        case QNode(head, kids @ _*) :: remains=> {
          val kidsNode = kids.flatMap {
            case HalfEdge(e,n) => Some(n)
            case _ => None
          }
          loopForNodes(kidsNode.toList ::: remains)
        }

        case QNodeMarker(head, marker, kids @ _*) :: remains => {
          markers.update(marker,head)
          val kidsNode = kids.flatMap {
            case HalfEdge(e,n) => Some(n)
            case _ => None
          }
          loopForNodes(kidsNode.toList ::: remains)
        }
        case QNodeRef(rf, kids @ _*) :: remains => {
          val kidsNode = kids.flatMap {
            case HalfEdge(e,n) => Some(n)
            case _ => None
          }
          loopForNodes(kidsNode.toList ::: remains)
        }
        case Nil =>
      }
    }
    //
    loopForNodes(qNodes.toList)
    var nodeIndex = -1
    val markersNode = markers.map{case(a,b) => {
      nodeIndex += 1
      a -> Node(b,nodeIndex)
    }}
    val nodes = mutable.MutableList.empty[Node[N]]
    val edges = mutable.MutableList.empty[DEdge[E]]
    @tailrec
    def loopForGraph(nds:List[(QNodeLike[N,E], Node[N])]):Unit = {
      nds match {
        case (x, node) :: remains => {
          val kids = x match {
            case QNode(head, kds @_*) => kds
            case QNodeMarker(head, mrk, kds @_*) => kds
            case QNodeRef(mrk, kds @_*) => kds
            case _ => List.empty
          }
          val outNodes =
            kids.flatMap {
              case HalfEdge(e,n) => {

                val tup = n match {
                  case QNode(n1, x @_*) => {
                    nodeIndex += 1
                    val nd = Node(n1,nodeIndex)
                    nodes += nd
                    Some((n,nd))
                  }
                  case QNodeMarker(n1,mrk,x @_*) => {
                    val mrkNode = markersNode(mrk)
                    Some((n,mrkNode))
                  }
                  case QNodeRef(mrk,x @_*) => {
                    val mrkNode = markersNode(mrk)
                    Some((n,mrkNode))
                  }
                }
                edges += DEdge(e,node.id,tup.get._2.id)
                tup
              }
              case _ => None
            }
          loopForGraph(outNodes.toList ::: remains)
        }
        case Nil =>

      }
    }

    val nodes2Begin = qNodes.flatMap {
      n => n match {
        case QNode(n1, x @_*) => {
          nodeIndex += 1
          val nd = Node(n1, nodeIndex)
          nodes += nd
          Some((n, nd))
        }
        case QNodeMarker(n1, mrk, x @_*) => {
          val mrkNode = markersNode(mrk)
          Some((n, mrkNode))
        }
        case QNodeRef(mrk, x @_*) => {
          val mrkNode = markersNode(mrk)
          Some((n, mrkNode))
        }
      }
    }


    loopForGraph(nodes2Begin.toList)
    val allNodes = nodes.toList ::: markersNode.values.toList

    DGraph.from(allNodes.map(n => n.id -> n).toMap,TreeMap(edges.map(e => (e.from,e.to) -> e):_*))
  }




  private def mkEdgeMaps[N,E](nodes:Map[Int, Node[N]], edges:TreeMap[(Int,Int), DEdge[E]])
    :(Map[Int,IndexedSeq[Int]], Map[Int,IndexedSeq[Int]]) = {
    val inM = scala.collection.mutable.Map.empty[Int,IndexedSeq[Int]]
    val outM = scala.collection.mutable.Map.empty[Int,IndexedSeq[Int]]

    for(e <- edges.values) {
      //update IN
      if(outM.contains(e.from)) outM.update(e.from, outM(e.from) :+ e.to)
      else outM.update(e.from, IndexedSeq(e.to))
      //update Out
      if(inM.contains(e.to)) inM.update(e.to, inM(e.to) :+ e.from)
      else inM.update(e.to, IndexedSeq(e.from))
    }

    for(n <- nodes.values) {
      if(!outM.contains(n.id)) outM.update(n.id, IndexedSeq())
      if(!inM.contains(n.id)) inM.update(n.id, IndexedSeq())
    }

    (inM.toMap, outM.toMap)
  }



  //def create[N,E](nodes:N*)(implicit xs:NodeCreator[N]) = { nodes.map(n => Node(n,2) -->Node(n,3) ) }
  def empty[N,E]() = DGraph.from(Map.empty[Int, Node[N]], TreeMap.empty[(Int,Int), DEdge[E]])
  def sas() = {

  }

}

