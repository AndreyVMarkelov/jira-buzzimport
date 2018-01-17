package ru.andreymarkelov.atlas.plugins.buzzimport.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class BuzzImportUpload extends HttpServlet{
    private static final Logger log = LoggerFactory.getLogger(BuzzImportUpload.class);

    @Override
    protected void doGet(
            HttpServletRequest req,
            HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html");
        resp.getWriter().write("<html><body>Hello World</body></html>");
    }
}
