package org.scalaquery.test

import org.junit.Test
import org.junit.Assert._
import org.scalaquery.ql._
import org.scalaquery.ql.TypeMapper._
import org.scalaquery.ql.extended.{ExtendedTable => Table}
import org.scalaquery.session.Database.threadLocalSession
import org.scalaquery.simple.StaticQuery._
import org.scalaquery.ast._
import org.scalaquery.test.util._
import org.scalaquery.test.util.TestDB._

object NewQuerySemanticsTest extends DBTestObject(H2Mem)

class NewQuerySemanticsTest(tdb: TestDB) extends DBTest(tdb) {
  import tdb.driver.Implicit._

  @Test def test(): Unit = db withSession {

    val SuppliersStd = new Table[(Int, String, String, String, String, String)]("SUPPLIERS") {
      def id = column[Int]("SUP_ID", O.PrimaryKey) // This is the primary key column
      def name = column[String]("SUP_NAME")
      def street = column[String]("STREET")
      def city = column[String]("CITY")
      def state = column[String]("STATE")
      def zip = column[String]("ZIP")
      def * = id ~ name ~ street ~ city ~ state ~ zip
    }

    val CoffeesStd = new Table[(String, Int, Double, Int, Int)]("COFFEES") {
      def name = column[String]("COF_NAME", O.PrimaryKey)
      def supID = column[Int]("SUP_ID")
      def price = column[Double]("PRICE")
      def sales = column[Int]("SALES")
      def total = column[Int]("TOTAL")
      def * = name ~ supID ~ price ~ sales ~ total
      def supplier = foreignKey("SUP_FK", supID, SuppliersStd)(_.id)
    }

    val Suppliers = new Table[(Int, String, String)]("SUPPLIERS") {
      def id = column[Int]("SUP_ID", O.PrimaryKey) // This is the primary key column
      def name = column[String]("SUP_NAME")
      def street = column[String]("STREET")
      def city = column[String]("CITY")
      def state = column[String]("STATE")
      def zip = column[String]("ZIP")
      def * = id ~ name ~ street
      def forInsert = id ~ name ~ street ~ city ~ state ~ zip
    }

    val Coffees = new Table[(String, Int, Double, Int, Int)]("COFFEES") {
      def name = column[String]("COF_NAME", O.PrimaryKey)
      def supID = column[Int]("SUP_ID")
      def price = column[Double]("PRICE")
      def sales = column[Int]("SALES")
      def total = column[Int]("TOTAL")
      def * = name ~ supID ~ price ~ sales ~ (total * 10)
      def forInsert = name ~ supID ~ price ~ sales ~ total
      def totalComputed = sales.asColumnOf[Double] * price
      def supplier = foreignKey("SUP_FK", supID, Suppliers)(_.id)
    }

    (SuppliersStd.ddl ++ CoffeesStd.ddl).create
    (SuppliersStd.ddl ++ CoffeesStd.ddl).createStatements.foreach(s => println("create: "+s))

    SuppliersStd.insert(101, "Acme, Inc.",      "99 Market Street", "Groundsville", "CA", "95199")
    SuppliersStd.insert( 49, "Superior Coffee", "1 Party Place",    "Mendocino",    "CA", "95460")
    SuppliersStd.insert(150, "The High Ground", "100 Coffee Lane",  "Meadows",      "CA", "93966")

    CoffeesStd.insertAll(
      ("Colombian",         101, 7.99, 0, 0),
      ("French_Roast",       49, 8.99, 0, 0),
      ("Espresso",          150, 9.99, 0, 0),
      ("Colombian_Decaf",   101, 8.99, 0, 0),
      ("French_Roast_Decaf", 49, 9.99, 0, 0)
    )

    val l1 = queryNA[String]("select c.cof_name from coffees c").list
    println("l1: "+l1)
    assertEquals(5, l1.length)
    val l2 = queryNA[String]("select c.cof_name from coffees c, suppliers s").list
    println("l2: "+l2)
    assertEquals(15, l2.length)

    def show(name: String, g: NodeGenerator) {
      val n = Node(g)
      //AnonSymbol.assignNames(n, force = true)
      println("=========================================== "+name)
      n.dump("source: ")
      val n2 = Optimizer.standard.apply(n)
      println
      if(n2 ne n) {
        n2.dump("optimized: ")
        println
      }

      val n3 = Columnizer(n2)
      if(n3 ne n2) {
        n3.dump("columnized: ")
        println
      }

      val n4 = Optimizer.assignUniqueSymbols(n3)
      if(n4 ne n3) {
        AnonSymbol.assignNames(n4, "u")
        n4.dump("unique symbols: ")
        println
      }
      val n5 = RewriteGenerators(n4)
      if(n5 ne n4) {
        AnonSymbol.assignNames(n5, "c")
        n5.dump("generators rewritten: ")
        println
      }
      val n6 = Optimizer.aliasTableColumns(n5)
      if(n6 ne n5) {
        AnonSymbol.assignNames(n6, "a")
        n6.dump("aliased: ")
        println
      }
      val n7 = Comprehension.toComprehensions(n6)
      if(n7 ne n6) {
        n7.dump("comprehensions: ")
        println
      }
    }

    val q1 = for {
      c <- Query(Coffees).take(3)
      s <- Suppliers
    } yield (c.name ~ (s.city ++ ":"), c, s, c.totalComputed)
    show("q1: Plain implicit join", q1)
    //println("q1: "+q1.selectStatement)
    //val l3 = q1.list
    //println("l3: "+l3)

    val q1b_0 = Query(Coffees).take(3) join Suppliers on (_.supID === _.id)
    val q1b = for {
      (c, s) <- q1b_0.take(2).filter(_._1.name =!= "foo")
      (c2, s2) <- q1b_0
    } yield c.name ~ s.city ~ c2.name
    show("q1b: Explicit join with condition", q1b)

    val q2 = for {
      c <- Coffees.filter(_.price < 9.0).map(_.*)
      s <- Suppliers if s.id === c._2
    } yield c._1 ~ s.name
    show("q2: More elaborate query", q2)

    val q3 = Coffees.flatMap { c =>
      val cf = c.where(_.price < c.price)
      cf.flatMap { cf =>
        Suppliers.where(_.id === c.supID).map { s =>
          c.name ~ s.name ~ cf.name ~ cf.total ~ cf.totalComputed
        }
      }
    }
    show("q3: Lifting scalar values", q3)

    /*val q3b = Coffees.flatMap { c =>
      val cf = Query((c, 42)).where(_._1.price < 9.0)
      cf.flatMap { case (cf, num) =>
        Suppliers.where(_.id === c.supID).map { s =>
          c.name ~ s.name ~ cf.name ~ cf.total ~ cf.totalComputed ~ num
        }
      }
    }
    show("q3b: Lifting scalar values, with extra tuple", q3b)

    val q4 = for {
      c <- Coffees.map(c => (c.name, c.price, 42)).take(100).filter(_._2 < 9.0)
    } yield c._1 ~ c._3
    show("q4: Map to tuple, then filter", q4)

    val q4b_0 = Coffees.map(c => (c.name, c.price, 42)).filter(_._2 < 9.0)
    val q4b = for {
      c <- q4b_0
      d <- q4b_0
    } yield (c,d)
    show("q4b: Map to tuple, then filter, with self-join", q4b)

    val q5_0 = Query(Coffees).take(10)
    val q5 = for {
      c1 <- q5_0
      c2 <- q5_0
    } yield (c1, c2)
    show("q5: Implicit self-join", q5)

    val q5b = for {
      t <- q5_0 join q5_0 on (_.name === _.name)
    } yield (t._1, t._2)
    show("q5b: Explicit self-join with condition", q5b)

    val q6 = Coffees.flatMap(c => Query(Suppliers))
    show("q6: Unused outer query result, unbound TableQuery", q6)

    val q7 = for {
      c <- Query(Coffees).take(10).map((_, 1)) union Query(Coffees).drop(4).map((_, 2))
    } yield c._1.name ~ c._1.supID ~ c._2
    show("q7: Union", q7)*/

    /*val q7b = q7 where (_._1 =!= "Colombian")
    show("q7b: Union with filter on the outside", q7b)*/

    (SuppliersStd.ddl ++ CoffeesStd.ddl).drop
    (SuppliersStd.ddl ++ CoffeesStd.ddl).dropStatements.foreach(s => println("drop: "+s))
  }
}
