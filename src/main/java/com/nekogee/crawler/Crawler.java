package com.nekogee.crawler;


import okhttp3.*;
import okhttp3.internal.NamedRunnable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author nekogee
 * @since 2019/5/3.
 */

@Log4j2
public class Crawler {

    private final OkHttpClient client;

    private final Set<HttpUrl> fetchedUrls = Collections.synchronizedSet(new LinkedHashSet<>());//存放爬取过的URL
    private final LinkedBlockingQueue<HttpUrl> urlLinkedBlockingQueue = new LinkedBlockingQueue<>();//存放URL的线程安全的阻塞队列
    private final ConcurrentHashMap<String, AtomicInteger> hostNames = new ConcurrentHashMap<>();//存放域名的线程安全的HashMap

    private static String path = "C:\\Users\\nekogee\\Desktop\\crawler-cache";//用于缓存爬取的网页
    private static String url = "http://www2.scut.edu.cn/gzic/";//起始网址
    private static int threadCount = 3;//线程数
    private static long cacheByteCount = 1024L * 1024L * 100L;//限制最多100M的缓存
    private static Cache cache = new Cache(new File(path), cacheByteCount);


    private static AtomicInteger hrefFetchedCount = new AtomicInteger(0) ;
    private static AtomicInteger hrefSavedCount = new AtomicInteger(0);

    public Crawler(OkHttpClient client) {
        this.client = client;
    }

    /**
     * 创建多个线程爬取网页
     * @param threadCount 线程数
     */
    private void parallelDrainQueue(int threadCount) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.execute(new NamedRunnable("Thread %s", i) {
                @Override protected void execute() {
                    try {
                        drainQueue();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
        executor.shutdown();
    }

    /**
     * 从队列中获取url并访问
     * @param
     */
    private void drainQueue() throws Exception {
        for (HttpUrl url; !urlLinkedBlockingQueue.isEmpty(); ) {
            url = urlLinkedBlockingQueue.take();
            //将url存在set中，若url已访问过则continue，确保不重复访问url
            if (!fetchedUrls.add(url)) {
                continue;
            }
            Thread currentThread = Thread.currentThread();
            String originalName = currentThread.getName();
            currentThread.setName("Thread " + url.toString());
            try {
                parse(url);
            } catch (IOException e) {
                System.out.printf("Exception: %s %s%n", url, e);
            } finally {
                currentThread.setName(originalName);
            }
        }
    }

    /**
     * 向指定url获取内容并解析
     * @param url 指定url
     */
    private void parse(HttpUrl url) throws IOException {

        hrefFetchedCount.incrementAndGet();

        //创建request，并添加user-agent
        Request request = new Request.Builder()
                .addHeader("User-Agent", "NIR2019S-201630598879")
                .url(url)
                .build();

        //打印response状态并解析
        try (Response response = client.newCall(request).execute()) {
            String responseSource = response.networkResponse() != null ? ("(ststus: " + response.networkResponse().code() + ")") : "(cache)";

            int responseCode = response.code();
            //System.out.printf("%03d: %s %s%n", responseCode, url, responseSource);
            System.out.printf("%s%n", url);

            String contentType = response.header("Content-Type");

            //失败则打印错误日志
            if (responseCode != 200 || contentType == null) {
                log.info(url.toString()+" - Error "+ responseSource);
                return;
            }
            //成功则打印成功日志
            log.info(url.toString()+" - Successful");

            //非htm或html网页则返回
            MediaType mediaType = MediaType.parse(contentType);
            if (mediaType == null || !mediaType.subtype().contains("htm")) {
                return;
            }

            //使用Jsoup解析response
            Document document = Jsoup.parse(response.body().string(), url.toString());
            for (Element element : document.select("a[href]")) {
                String href = element.attr("href");

                HttpUrl link = response.request().url().resolve(href);
                if (link == null) continue;
                if (!link.host().contains("scut.edu.cn")) continue; //若非scut.edu.cn下的网页则不采集
                urlLinkedBlockingQueue.add(link.newBuilder().fragment(null).build());
                hrefSavedCount.incrementAndGet();
            }
            if(hrefFetchedCount.get() % 50 == 0) {
                System.out.println("hrefFetchedCount: "+ hrefFetchedCount + " hrefSavedCount: " + hrefSavedCount);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .cache(cache)
                .build();

        Crawler crawler = new Crawler(client);
        crawler.urlLinkedBlockingQueue.add(HttpUrl.parse(url));
        crawler.parallelDrainQueue(threadCount);
    }
}