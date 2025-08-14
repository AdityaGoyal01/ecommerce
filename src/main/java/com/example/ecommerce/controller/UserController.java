package com.example.ecommerce.controller;


import org.springframework.ui.Model;
import com.example.ecommerce.entity.CartItem;
import com.example.ecommerce.entity.Order;
import com.example.ecommerce.entity.Product;
import com.example.ecommerce.repository.OrderRepository;
import com.example.ecommerce.repository.ProductRepository;
import com.example.ecommerce.service.EmailService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/user")
@SessionAttributes("cart")
public class UserController {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private OrderRepository orderRepository;

    // Initialize cart in session
    @ModelAttribute("cart")
    public List<Product> cart() {
        return new ArrayList<>();
    }

    @GetMapping("/dashboard")
    public String userDashboard(Model model) {
        model.addAttribute("products", productRepository.findAll());
        return "user-dashboard";
    }

    @GetMapping("/add-to-cart/{id}")
    public String addToCart(@PathVariable Long id, @ModelAttribute("cart") List<Product> cart,
                            HttpSession session,
                            RedirectAttributes redirectAttributes) {
        Product product = productRepository.findById(id).orElse(null);

        if (product != null) {
// Get or initialize a temporary stock map in session
            Map<Long, Integer> reservedStock = (Map<Long, Integer>) session.getAttribute("reservedStock");
            if (reservedStock == null) {
                reservedStock = new HashMap<>();
            }

            int alreadyReserved = reservedStock.getOrDefault(product.getId(), 0);

// Check against real stock in DB
            if (alreadyReserved < product.getStock()) {
                cart.add(product); // Add to cart visually

// Reserve 1 quantity (not save in DB)
                reservedStock.put(product.getId(), alreadyReserved + 1);
                session.setAttribute("reservedStock", reservedStock);

                redirectAttributes.addFlashAttribute("success", "Product added to cart.");
            } else {
                redirectAttributes.addFlashAttribute("error", "Product is out of stock.");
            }
        }

        return "redirect:/user/dashboard";
    }


    @GetMapping("/cart")
    public String viewCart(@ModelAttribute("cart") List<Product> cart, Model model) {
        double total = cart.stream().mapToDouble(Product::getPrice).sum();
        model.addAttribute("cart", cart);
        model.addAttribute("total", total);
        return "cart";
    }

    @GetMapping("/cart/remove/{index}")
    public String removeItem(@PathVariable int index, @ModelAttribute("cart") List<Product> cart,
                             HttpSession session) {
        if (index >= 0 && index < cart.size()) {
            Product removedProduct = cart.remove(index);

// Restore reserved stock in session
            Map<Long, Integer> reservedStock = (Map<Long, Integer>) session.getAttribute("reservedStock");
            if (reservedStock == null) {
                reservedStock = new HashMap<>();
            }

            Long productId = removedProduct.getId();
            int reservedQty = reservedStock.getOrDefault(productId, 0);

            if (reservedQty > 0) {
                reservedStock.put(productId, reservedQty - 1);
            }

// Update session
            session.setAttribute("reservedStock", reservedStock);
        }

        return "redirect:/user/cart";
    }

    @GetMapping("/checkout")
    public String checkout(@ModelAttribute("cart") List<Product> cart,
                           Model model,
                           Authentication authentication,
                           SessionStatus sessionStatus) {
        if (cart.isEmpty()) {
            model.addAttribute("message", "Cart is empty!");
            return "redirect:/user/cart";
        }

        Order order = new Order();
        order.setUserEmail(authentication.getName());
        order.setOrderDate(LocalDateTime.now());
        double total = 0;

        for (Product p : cart) {
// Fetch latest product from DB (for correct stock update)
            Product dbProduct = productRepository.findById(p.getId()).orElse(null);
            if (dbProduct == null || dbProduct.getStock() < 1) {
                model.addAttribute("message", "Insufficient stock for: " + p.getName());
                return "redirect:/user/cart";
            }

// Decrement actual stock
            dbProduct.setStock(dbProduct.getStock() - 1);
            productRepository.save(dbProduct);

// Create order item
            CartItem item = new CartItem();
            item.setProduct(dbProduct);
            item.setPrice(dbProduct.getPrice());
            item.setQuantity(1); // for now, quantity = 1
            item.setOrder(order);
            total += dbProduct.getPrice();
            order.getItems().add(item);
        }

        order.setTotalAmount(total);
        orderRepository.save(order);

// Clear cart
        sessionStatus.setComplete();

// Send email
        emailService.sendOrderEmail(
                authentication.getName(),
                "Order Confirmation - E-commerce",
                "Thank you for your order! Order ID: " + order.getId()
        );

        return "redirect:/user/orders";
    }

    @GetMapping("/orders")
    public String userOrders(Model model, Authentication authentication) {
        List<Order> orders = orderRepository.findByUserEmail(authentication.getName());
        model.addAttribute("orders", orders);
        return "order-history";
    }
}