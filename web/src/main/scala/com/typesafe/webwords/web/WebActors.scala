package com.typesafe.webwords.web

import scala.collection.mutable
import scala.xml
import scala.xml.Attribute
import akka.actor.{ Index => _, _ }
import akka.actor.Actor.actorOf
import akka.http._
import com.typesafe.webwords.common._
import java.net.URL
import java.net.URI
import java.net.MalformedURLException
import java.net.URISyntaxException
import javax.servlet.http.HttpServletResponse

// this is just here for testing a simple case.
class HelloActor extends Actor {
    override def receive = {
        case get: Get =>
            get OK "hello!"
        case request: RequestMethod =>
            request NotAllowed "unsupported request"
    }
}

// we send any paths we don't recognize to this one.
class Custom404Actor extends Actor {
    override def receive = {
        case request: RequestMethod =>
            // you could do something nicer ;-) this is just an example
            request NotFound "Nothing here!"
    }
}

// this actor handles the main page.
class WordsActor(config: WebWordsConfig) extends Actor {
    private val client = actorOf(new ClientActor(config))

    case class Finish(request: RequestMethod, url: String, index: Option[Index],
        cacheHit: Boolean, startTime: Long)

    private def form(url: String, skipCache: Boolean, badUrl: Boolean = false) = {
      val css = scala.xml.Unparsed("""
    bottom:0;
    position:fixed;
    z-index:150;
    _position:absolute;
    _top:expression(eval(document.documentElement.scrollTop+
        (document.documentElement.clientHeight-this.offsetHeight)));
    height:80px;
          """)
        <div style={css}>
            <form action="/words" method="get">
                <fieldset>
                    <div>
                        <label for="url">Site</label>
                        <input type="text" id="url" name="url" value={ url } style="min-width: 300px;"></input>
                        {
                            if (badUrl) {
                                <div style="font-color: red;">Invalid or missing URL</div>
                            }
                        }
                    </div>
                    <div>
                        {
                            <input type="checkbox" id="skipCache" name="skipCache"></input> %
                                (if (skipCache) Attribute("checked", xml.Text(""), xml.Null) else xml.Null)
                        }
                        <label for="skipCache">Skip cache</label>
                    </div>
                    <div>
                        <button>Spider &amp; Index</button>
                    </div>
                </fieldset>
            </form>
        </div>
    }

    def wordsPage(formNode: xml.NodeSeq, resultsNode: xml.NodeSeq) = {
        <html>
            <head>
                <title>Web Words!</title>
            </head>
            <body>
                <div>
                    <div>
                        { formNode }
                    </div>
                    {
                        if (resultsNode.nonEmpty)
                            <div>
                                { resultsNode }
                            </div>
                    }
                </div>
            </body>
        </html>
    }

    private def completeWithHtml(request: RequestMethod, html: xml.NodeSeq) = {
        request.response.setContentType("text/html")
        request.response.setCharacterEncoding("utf-8")
        request.OK("<!DOCTYPE html>\n" + html)
    }

    private def handleFinish(finish: Finish) = {
        val elapsed = System.currentTimeMillis - finish.startTime
        finish match {
            case Finish(request, url, Some(index), cacheHit, startTime) =>
                //val html = wordsPage(form(url, skipCache = false), Results.resultsv2(url, index, cacheHit, elapsed))
                val html = Results.resultsv2(url, index, cacheHit, elapsed, form(url, skipCache = false))

                completeWithHtml(request, html)

            case Finish(request, url, None, cacheHit, startTime) =>
                request.OK("Failed to index url in " + elapsed + "ms (try reloading)")
        }
    }

    private def parseURL(s: String): Option[URL] = {
        val maybe = try {
            val uri = new URI(s) // we want it to be a valid URI also
            val url = new URL(s)
            // apparently a valid URI can have no hostname
            if (uri.getHost() == null)
                throw new URISyntaxException(s, "No host in URI")
            Some(url)
        } catch {
            case e: MalformedURLException => None
            case e: URISyntaxException => None
        }
        maybe.orElse({
            if (s.startsWith("http"))
                None
            else
                parseURL("http://" + s)
        })
    }

    private def handleGet(get: RequestMethod) = {
        val skipCacheStr = Option(get.request.getParameter("skipCache")).getOrElse("false")
        val skipCache = Seq("true", "on", "checked").contains(skipCacheStr.toLowerCase)
        val urlStr = Option(get.request.getParameter("url"))
        val url = parseURL(urlStr.getOrElse(""))

        if (url.isDefined) {
            val startTime = System.currentTimeMillis
            val futureGotIndex = client ? GetIndex(url.get.toExternalForm, skipCache)

            futureGotIndex foreach {
                // now we're in another thread, so we just send ourselves
                // a message, don't touch actor state
                case GotIndex(url, indexOption, cacheHit) =>
                    self ! Finish(get, url, indexOption, cacheHit, startTime)
            }

            // we have to worry about timing out also.
            futureGotIndex onTimeout { _ =>
                // again in another thread - most methods on futures are in another thread!
                self ! Finish(get, url.get.toExternalForm, index = None, cacheHit = false, startTime = startTime)
            }
        } else {
            val html = wordsPage(form(urlStr.getOrElse(""), skipCache, badUrl = urlStr.isDefined),
                resultsNode = xml.NodeSeq.Empty)

            completeWithHtml(get, html)
        }
    }

    override def receive = {
        case get: Get =>
            handleGet(get)
        case request: RequestMethod =>
            request NotAllowed "unsupported request"
        case finish: Finish =>
            handleFinish(finish)
    }

    override def preStart = {
        client.start
    }

    override def postStop = {
        client.stop
    }
}

// This actor simply delegates to the real handlers.
// There are extra libraries such as Spray that make this less typing:
//   https://github.com/spray/spray/wiki
// but for this example, showing how you would do it manually.
class WebBootstrap(rootEndpoint: ActorRef, config: WebWordsConfig) extends Actor with Endpoint {
    private val handlers = Map(
        "/hello" -> actorOf[HelloActor],
        "/words" -> actorOf(new WordsActor(config)))

    private val custom404 = actorOf[Custom404Actor]

    // Caution: this callback does not run in the actor thread,
    // so has to be thread-safe. We keep it simple and only touch
    // immutable values so there's nothing to worry about.
    private val handlerFactory: PartialFunction[String, ActorRef] = {
        case path if handlers.contains(path) =>
            handlers(path)
        case "/" =>
            handlers("/words")
        case path: String =>
            custom404
    }

    override def receive = handleHttpRequest

    override def preStart = {
        // start up our handlers
        handlers.values foreach { _.start }
        custom404.start

        // register ourselves with the akka-http RootEndpoint actor.
        // In Akka 2.0, Endpoint.Attach takes a partial function,
        // in 1.2 it still takes two separate functions.
        // So in 2.0 this can just be Endpoint.Attach(handlerFactory)
        rootEndpoint ! Endpoint.Attach({
            path =>
                handlerFactory.isDefinedAt(path)
        }, {
            path =>
                handlerFactory(path)
        })
    }

    override def postStop = {
        handlers.values foreach { _.stop }
        custom404.stop
    }
}

object Results {
  import com.typesafe.config._
  
  val config = ConfigFactory.load
  // "https://raw.github.com/opyate/pinelo/master/pinelo"
  val scriptLocationPinelo = config.getString("script.location.pinelo")
  
  def resultsv1(url: String, index: Index, cacheHit: Boolean, elapsed: Long) = {
        // world's ugliest word cloud!
        def countToStyle(count: Int) = {
            val maxCount = (index.wordCounts.headOption map { _._2 }).getOrElse(1)
            val font = 6 + ((count.doubleValue / maxCount.doubleValue) * 24).intValue
            Attribute("style", xml.Text("font-size: " + font + "pt;"), xml.Null)
        }

        <div>
            <p>
                <a href={ url }>{ url }</a>
                spidered and indexed.
            </p>
            <p>{ elapsed }ms elapsed.</p>
            <p>{ index.links.size } links scraped.</p>
            {
                if (cacheHit)
                    <p>Results were retrieved from cache.</p>
                else
                    <p>Results newly-spidered (not from cache).</p>
            }
        </div>
        <h3>Word Counts</h3>
        <div style="max-width: 600px; margin-left: 100px; margin-top: 20px; margin-bottom: 20px;">
            {
                val nodes = xml.NodeSeq.newBuilder
                for ((word, count) <- index.wordCounts) {
                    nodes += <span title={ count.toString }>{ word }</span> % countToStyle(count)
                    nodes += xml.Text(" ")
                }
                nodes.result
            }
        </div>
        <div style="font-size: small">(hover to see counts)</div>
        <h3>Links Found</h3>
        <div style="margin-left: 50px;">
            <ol>
                {
                    val nodes = xml.NodeSeq.newBuilder
                    for ((text, url) <- index.links)
                        nodes += <li><a href={ url }>{ text }</a></li>
                    nodes.result
                }
            </ol>
        </div>
    }
  
  def resultsv2(url: String, index: Index, cacheHit: Boolean, elapsed: Long, formNode: xml.NodeSeq) = {
    val (_firstWord,firstIndex) = index.wordCounts.head
    val firstWord = if (_firstWord.size <= 5) _firstWord + "     " else _firstWord
    
    val brushes = scala.xml.Unparsed("""var BRUSH_INCLUDES = ["simple", "arc"];""")
    val word = scala.xml.Unparsed("""var THEWORD = "%s";""".format(firstWord))
    val scriptLoc = scala.xml.Unparsed("""var SCRIPTLOC = "%s";""".format(scriptLocationPinelo))
    val css = scala.xml.Unparsed("""canvas{position: absolute;left:0;top:0;}""")
    
<html>
<head>
	<title>opyate XMAS hack 2012</title>
	<link rel="stylesheet" href="http://code.jquery.com/ui/1.9.2/themes/base/jquery-ui.css" type="text/css" />
	<style type="text/css">{css}</style>
</head>
<body>
    
	<div id="background-canvas">
    </div>

    {formNode}    

	<script type="text/javascript" src="//ajax.googleapis.com/ajax/libs/jquery/1.8.3/jquery.min.js"></script>
	<script type="text/javascript" src="//code.jquery.com/ui/1.9.2/jquery-ui.js"></script>
	<script src="//cdnjs.cloudflare.com/ajax/libs/raphael/2.1.0/raphael-min.js"></script>
	<script src={scriptLocationPinelo + "/libre.js"}></script>
	<script type="text/javascript" src={scriptLocationPinelo + "/mouse.js"}></script>
	<script type="text/javascript">
		// tell Pinelo which brush to use
		{brushes}
		{word}
		{scriptLoc}
	</script>
	<script type="text/javascript" src={scriptLocationPinelo + "/src/inheritance.js"}></script>
	<script type="text/javascript" src={scriptLocationPinelo + "/src/util.js"}></script>
	<script type="text/javascript" src={scriptLocationPinelo + "/src/setting.js"}></script>
	<script type="text/javascript" src={scriptLocationPinelo + "/src/layer.js"}></script>
	<script type="text/javascript" src={scriptLocationPinelo + "/src/brush.js"}></script>
	<script type="text/javascript" src={scriptLocationPinelo + "/src/palette.js"}></script>
	<script type="text/javascript" src={scriptLocationPinelo + "/hack.js"}></script>
	<script type="text/javascript" src={scriptLocationPinelo + "/draw.js"}></script>
</body>
</html>

  }
}
